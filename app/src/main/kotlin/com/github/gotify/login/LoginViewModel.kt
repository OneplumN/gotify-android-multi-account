package com.github.gotify.login

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.gotify.Settings
import com.github.gotify.api.Callback
import com.github.gotify.api.ClientFactory
import com.github.gotify.client.ApiClient
import com.github.gotify.client.api.ClientApi
import com.github.gotify.client.api.UserApi
import com.github.gotify.client.model.ClientParams
import com.github.gotify.client.model.GotifyInfo
import com.github.gotify.client.model.OIDCExternalAuthorizeRequest
import com.github.gotify.client.model.OIDCExternalTokenRequest
import com.github.gotify.client.model.User
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.tinylog.kotlin.Logger

internal class LoginViewModel(private val settings: Settings) : ViewModel() {

    private val _state = MutableLiveData<LoginState>(LoginState.UrlInput)
    val state: LiveData<LoginState> = _state

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    var gotifyInfo: GotifyInfo? = null
        private set

    private var authenticatedClient: ApiClient? = null
    private var authenticatedUser: User? = null

    fun invalidateUrl() {
        gotifyInfo = null
        _state.value = LoginState.UrlInput
    }

    fun checkUrl(url: String) {
        _state.value = LoginState.CheckingUrl
        settings.loginUrl = url

        try {
            ClientFactory.infoApi(settings, settings.loginSslSettings(), url)
                .info
                .enqueue(
                    Callback.call(
                        onSuccess = Callback.SuccessBody { info ->
                            gotifyInfo = info
                            _state.value = LoginState.Ready
                        },
                        onError = { _ -> tryVersionEndpoint(url) }
                    )
                )
        } catch (e: Exception) {
            tryVersionEndpoint(url)
        }
    }

    private fun tryVersionEndpoint(url: String) {
        try {
            ClientFactory.infoApi(settings, settings.loginSslSettings(), url)
                .version
                .enqueue(
                    Callback.call(
                        onSuccess = Callback.SuccessBody { version ->
                            gotifyInfo = GotifyInfo()
                                .version(version.version)
                                .oidc(false)
                                .register(false)
                            _state.value = LoginState.Ready
                        },
                        onError = { exception ->
                            _state.value = LoginState.UrlInput
                            _events.trySend(
                                LoginEvent.VersionError(url, exception.code)
                            )
                        }
                    )
                )
        } catch (e: Exception) {
            _state.value = LoginState.UrlInput
            _events.trySend(
                LoginEvent.VersionException(url, e.message ?: "")
            )
        }
    }

    fun login(username: String, password: String) {
        _state.value = LoginState.LoggingIn

        val client = ClientFactory.basicAuth(
            settings,
            settings.loginSslSettings(),
            username,
            password,
            settings.loginUrl
        )
        authenticatedClient = client
        client.createService(UserApi::class.java)
            .currentUser()
            .enqueue(
                Callback.call(
                    onSuccess = Callback.SuccessBody { user ->
                        authenticatedUser = user
                        _state.value = LoginState.WaitingForClientName
                        _events.trySend(LoginEvent.ShowClientNameDialog)
                    },
                    onError = {
                        authenticatedClient = null
                        authenticatedUser = null
                        _state.value = LoginState.Ready
                        _events.trySend(LoginEvent.InvalidCredentials)
                    }
                )
            )
    }

    fun createClient(name: String) {
        val client = authenticatedClient ?: return
        _state.value = LoginState.CreatingClient

        client.createService(ClientApi::class.java)
            .createClient(ClientParams().name(name))
            .enqueue(
                Callback.call(
                    onSuccess = Callback.SuccessBody { c ->
                        val user = authenticatedUser
                        saveLoggedInAccount(c.token, user?.name, user?.admin ?: false)
                        _events.trySend(LoginEvent.LoginSuccess)
                    },
                    onError = {
                        _state.value = LoginState.Ready
                        _events.trySend(LoginEvent.ClientCreationFailed)
                    }
                )
            )
    }

    fun cancelClientCreation() {
        authenticatedClient = null
        authenticatedUser = null
        _state.value = LoginState.Ready
    }

    fun startOidcAuthorize(clientName: String) {
        _state.value = LoginState.OidcAuthorizing

        val codeVerifier = PkceUtil.generateCodeVerifier()
        val codeChallenge = PkceUtil.generateCodeChallenge(codeVerifier)
        settings.oidcCodeVerifier = codeVerifier

        val request = OIDCExternalAuthorizeRequest()
            .codeChallenge(codeChallenge)
            .redirectUri(OIDC_REDIRECT_URI)
            .name(clientName)

        Logger.info(
            "OIDC: Requesting redirect url from gotify server: redirect: ${request.redirectUri}, challenge: ${request.codeChallenge}"
        )

        ClientFactory.oidcApi(settings, settings.loginSslSettings(), settings.loginUrl)
            .externalAuthorize(request)
            .enqueue(
                Callback.call(
                    onSuccess = Callback.SuccessBody { response ->
                        Logger.info(
                            "OIDC: Received redirect url: ${response.authorizeUrl}, state: ${response.state}"
                        )
                        settings.oidcState = response.state
                        _state.value = LoginState.OidcWaitingForCallback
                        _events.trySend(
                            LoginEvent.OpenBrowser(response.authorizeUrl)
                        )
                    },
                    onError = {
                        Logger.error("OIDC: authorize failed")
                        _state.value = LoginState.Ready
                        _events.trySend(LoginEvent.OidcAuthorizeFailed)
                    }
                )
            )
    }

    fun handleOidcCallback(code: String, oidcState: String) {
        val expectedState = settings.oidcState
        if (expectedState == null || expectedState != oidcState) {
            Logger.warn("OIDC: callback state mismatch (expected=$expectedState, got=$oidcState)")
            _events.trySend(LoginEvent.OidcTokenExchangeFailed)
            return
        }

        val verifier = settings.oidcCodeVerifier
        if (verifier == null) {
            Logger.warn("OIDC: callback missing code verifier")
            return
        }

        settings.oidcCodeVerifier = null
        settings.oidcState = null
        _state.value = LoginState.OidcExchangingToken

        val request = OIDCExternalTokenRequest()
            .code(code)
            .state(oidcState)
            .codeVerifier(verifier)

        Logger.info(
            "OIDC: requesting client token: code=${request.code}, state=${request.state}, code_verifier=${request.codeVerifier}"
        )

        ClientFactory.oidcApi(settings, settings.loginSslSettings(), settings.loginUrl)
            .externalToken(request)
            .enqueue(
                Callback.call(
                    onSuccess = Callback.SuccessBody { response ->
                        saveLoggedInAccount(
                            response.token,
                            response.user.name,
                            response.user.admin
                        )
                        Logger.info("OIDC: login successful as ${response.user.name}")
                        _events.trySend(LoginEvent.LoginSuccess)
                    },
                    onError = {
                        Logger.error("OIDC: token exchange failed")
                        _state.value = LoginState.Ready
                        _events.trySend(LoginEvent.OidcTokenExchangeFailed)
                    }
                )
            )
    }

    private fun saveLoggedInAccount(token: String, username: String?, admin: Boolean) {
        settings.saveAccountForLogin(
            token,
            username,
            admin,
            gotifyInfo?.version ?: "UNKNOWN"
        )
    }

    class Factory(private val settings: Settings) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(settings) as T
        }
    }

    companion object {
        const val OIDC_REDIRECT_URI = "gotify://oidc/callback"
    }
}
