package com.github.gotify.login

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.Utils
import com.github.gotify.api.CertUtils
import com.github.gotify.databinding.ActivityLoginBinding
import com.github.gotify.databinding.ClientNameDialogBinding
import com.github.gotify.init.InitializationActivity
import com.github.gotify.log.LogsActivity
import com.github.gotify.log.UncaughtExceptionHandler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.cert.X509Certificate
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.tinylog.kotlin.Logger

internal class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var settings: Settings
    private val viewModel: LoginViewModel by viewModels { LoginViewModel.Factory(settings) }

    private var caCertCN: String? = null
    private lateinit var advancedDialog: AdvancedDialog

    private val caDialogResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                require(result.resultCode == RESULT_OK) { "result was ${result.resultCode}" }
                requireNotNull(result.data) { "file path was null" }

                val uri = result.data!!.data ?: throw IllegalArgumentException("file path was null")
                val fileStream = contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("file path was invalid")
                val destinationFile = File(filesDir, CertUtils.CA_CERT_NAME)
                copyStreamToFile(fileStream, destinationFile)

                caCertCN = getNameOfCertContent(destinationFile) ?: "unknown"
                settings.loginCaCertPath = destinationFile.absolutePath
                advancedDialog.showRemoveCaCertificate(caCertCN!!)
            } catch (e: Exception) {
                Utils.showSnackBar(this, getString(R.string.select_ca_failed, e.message))
            }
        }

    private val clientCertDialogResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                require(result.resultCode == RESULT_OK) { "result was ${result.resultCode}" }
                requireNotNull(result.data) { "file path was null" }

                val uri = result.data!!.data ?: throw IllegalArgumentException("file path was null")
                val fileStream = contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("file path was invalid")
                val destinationFile = File(filesDir, CertUtils.CLIENT_CERT_NAME)
                copyStreamToFile(fileStream, destinationFile)

                settings.loginClientCertPath = destinationFile.absolutePath
                advancedDialog.showRemoveClientCertificate()
            } catch (e: Exception) {
                Utils.showSnackBar(this, getString(R.string.select_client_failed, e.message))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UncaughtExceptionHandler.registerCurrentThread()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Logger.info("Entering ${javaClass.simpleName}")
        settings = Settings(this)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        binding.gotifyUrlEditext.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun onTextChanged(s: CharSequence, i: Int, i1: Int, i2: Int) {
                viewModel.invalidateUrl()
            }
            override fun afterTextChanged(editable: Editable) {}
        })

        binding.checkurl.setOnClickListener { doCheckUrl() }
        binding.openLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        binding.advancedSettings.setOnClickListener { toggleShowAdvanced() }
        binding.login.setOnClickListener { doLogin() }
        binding.oidcLogin.setOnClickListener { doOidcLogin() }

        viewModel.state.observe(this) { render(it) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { handleEvent(it) }
            }
        }

        handleOidcCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOidcCallback(intent)
    }

    private fun render(state: LoginState) {
        binding.checkurlProgress.visibility = View.GONE
        binding.loginProgress.visibility = View.GONE
        binding.oidcLoginProgress.visibility = View.GONE
        binding.checkurl.visibility = View.VISIBLE
        binding.credentialGroup.visibility = View.GONE
        binding.oidcGroup.visibility = View.GONE

        when (state) {
            LoginState.UrlInput -> {
                binding.checkurl.text = getString(R.string.check_url)
            }
            LoginState.CheckingUrl -> {
                binding.checkurl.visibility = View.GONE
                binding.checkurlProgress.visibility = View.VISIBLE
            }
            LoginState.Ready -> {
                showReadyState()
            }
            LoginState.LoggingIn -> {
                showReadyState()
                binding.login.visibility = View.GONE
                binding.loginProgress.visibility = View.VISIBLE
            }
            LoginState.WaitingForClientName, LoginState.CreatingClient -> {
                showReadyState()
                binding.login.visibility = View.GONE
                binding.loginProgress.visibility = View.VISIBLE
            }
            LoginState.OidcAuthorizing -> {
                showReadyState()
                binding.oidcLogin.visibility = View.GONE
                binding.oidcLoginProgress.visibility = View.VISIBLE
            }
            LoginState.OidcWaitingForCallback -> {
                showReadyState()
            }
            LoginState.OidcExchangingToken -> {
                binding.checkurl.visibility = View.GONE
                binding.checkurlProgress.visibility = View.VISIBLE
            }
        }
    }

    private fun showReadyState() {
        val info = viewModel.gotifyInfo ?: return
        binding.checkurl.text = getString(R.string.found_gotify_version, info.version)
        binding.credentialGroup.visibility = View.VISIBLE
        if (info.oidc) {
            binding.oidcGroup.visibility = View.VISIBLE
        }
    }

    private fun handleEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.OpenBrowser -> {
                startActivity(Intent(Intent.ACTION_VIEW, event.url.toUri()))
            }
            LoginEvent.LoginSuccess -> {
                Utils.showSnackBar(this, getString(R.string.created_client))
                startActivity(Intent(this, InitializationActivity::class.java))
                finish()
            }
            LoginEvent.ShowClientNameDialog -> {
                showClientNameDialog(viewModel::createClient)
            }
            is LoginEvent.VersionError -> {
                Utils.showSnackBar(
                    this,
                    getString(
                        R.string.version_failed_status_code,
                        "${event.url}/version",
                        event.code
                    )
                )
            }
            is LoginEvent.VersionException -> {
                Utils.showSnackBar(
                    this,
                    getString(R.string.version_failed, "${event.url}/version", event.message)
                )
            }
            LoginEvent.InvalidCredentials -> {
                Utils.showSnackBar(this, getString(R.string.wronguserpw))
            }
            LoginEvent.ClientCreationFailed -> {
                Utils.showSnackBar(this, getString(R.string.create_client_failed))
            }
            LoginEvent.OidcAuthorizeFailed -> {
                Utils.showSnackBar(this, getString(R.string.oidc_authorize_failed))
            }
            LoginEvent.OidcTokenExchangeFailed -> {
                Utils.showSnackBar(this, getString(R.string.oidc_token_exchange_failed))
            }
        }
    }

    private fun doCheckUrl() {
        val url = binding.gotifyUrlEditext.text.toString().trim().trimEnd('/')
        val parsedUrl = url.toHttpUrlOrNull()
        if (parsedUrl == null) {
            Utils.showSnackBar(this, "Invalid URL (include http:// or https://)")
            return
        }
        if ("http" == parsedUrl.scheme) {
            showHttpWarning()
        }
        viewModel.checkUrl(url)
    }

    private fun doLogin() {
        val username = binding.usernameEditext.text.toString()
        val password = binding.passwordEditext.text.toString()
        viewModel.login(username, password)
    }

    private fun doOidcLogin() {
        showClientNameDialog(viewModel::startOidcAuthorize)
    }

    private fun showClientNameDialog(onConfirm: (String) -> Unit) {
        val clientDialogBinding = ClientNameDialogBinding.inflate(layoutInflater)
        val clientDialogEditext = clientDialogBinding.clientNameEditext
        clientDialogEditext.setText(Build.MODEL)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_client_title)
            .setMessage(R.string.create_client_message)
            .setView(clientDialogBinding.root)
            .setPositiveButton(R.string.create) { _, _ ->
                onConfirm(clientDialogEditext.text.toString())
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                viewModel.cancelClientCreation()
            }
            .setCancelable(false)
            .show()
    }

    private fun handleOidcCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.toString().startsWith(LoginViewModel.OIDC_REDIRECT_URI)) return

        val code = data.getQueryParameter("code")
        val state = data.getQueryParameter("state")
        if (code == null || state == null) {
            Logger.warn(
                "OIDC callback missing parameters (code=${code != null}, state=${state != null})"
            )
            return
        }

        viewModel.handleOidcCallback(code, state)
    }

    private fun toggleShowAdvanced() {
        advancedDialog = AdvancedDialog(this, layoutInflater)
            .onDisableSSLChanged { _, disable ->
                viewModel.invalidateUrl()
                settings.loginValidateSSL = !disable
            }
            .onClickSelectCaCertificate {
                viewModel.invalidateUrl()
                doSelectCertificate(caDialogResultLauncher, R.string.select_ca_file)
            }
            .onClickRemoveCaCertificate {
                viewModel.invalidateUrl()
                settings.loginCaCertPath = null
                caCertCN = null
            }
            .onClickSelectClientCertificate {
                viewModel.invalidateUrl()
                doSelectCertificate(clientCertDialogResultLauncher, R.string.select_client_file)
            }
            .onClickRemoveClientCertificate {
                viewModel.invalidateUrl()
                settings.loginClientCertPath = null
            }
            .onClose { newPassword ->
                settings.loginClientCertPassword = newPassword
            }
            .show(
                !settings.loginValidateSSL,
                settings.loginCaCertPath,
                caCertCN,
                settings.loginClientCertPath,
                settings.loginClientCertPassword
            )
    }

    private fun doSelectCertificate(
        resultLauncher: ActivityResultLauncher<Intent>,
        @StringRes descriptionId: Int
    ) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            resultLauncher.launch(Intent.createChooser(intent, getString(descriptionId)))
        } catch (_: ActivityNotFoundException) {
            Utils.showSnackBar(this, getString(R.string.please_install_file_browser))
        }
    }

    private fun showHttpWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.warning)
            .setCancelable(true)
            .setMessage(R.string.http_warning)
            .setPositiveButton(R.string.i_understand, null)
            .show()
    }

    private fun getNameOfCertContent(file: File): String? {
        val ca = FileInputStream(file).use { CertUtils.parseCertificate(it) }
        return (ca as X509Certificate).subjectX500Principal.name
    }

    private fun copyStreamToFile(inputStream: InputStream, file: File) {
        FileOutputStream(file).use { inputStream.copyTo(it) }
    }
}
