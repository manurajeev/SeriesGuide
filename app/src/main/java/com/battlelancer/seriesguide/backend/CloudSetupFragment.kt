package com.battlelancer.seriesguide.backend

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.battlelancer.seriesguide.R
import com.battlelancer.seriesguide.SgApp
import com.battlelancer.seriesguide.backend.settings.HexagonSettings
import com.battlelancer.seriesguide.databinding.FragmentCloudSetupBinding
import com.battlelancer.seriesguide.sync.SgSyncAdapter
import com.battlelancer.seriesguide.sync.SyncProgress
import com.battlelancer.seriesguide.traktapi.ConnectTraktActivity
import com.battlelancer.seriesguide.util.Errors
import com.battlelancer.seriesguide.util.Utils
import com.battlelancer.seriesguide.util.safeShow
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.ErrorCodes
import com.firebase.ui.auth.IdpResponse
import com.google.android.gms.tasks.Task
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import timber.log.Timber

/**
 * Helps connecting a device to Hexagon: sign in via Google account, initial uploading of shows.
 */
class CloudSetupFragment : Fragment() {

    private var binding: FragmentCloudSetupBinding? = null

    private var snackbar: Snackbar? = null

    private var signInAccount: FirebaseUser? = null
    private lateinit var hexagonTools: HexagonTools

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hexagonTools = SgApp.getServicesComponent(requireContext()).hexagonTools()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCloudSetupBinding.inflate(inflater, container, false)

        binding!!.textViewCloudWarnings.setOnClickListener {
            // link to trakt account activity which has details about disabled features
            startActivity(Intent(context, ConnectTraktActivity::class.java))
        }

        binding!!.buttonCloudRemoveAccount.setOnClickListener {
            if (RemoveCloudAccountDialogFragment().safeShow(
                    parentFragmentManager,
                    "remove-cloud-account"
                )) {
                setProgressVisible(true)
            }
        }

        updateViews()
        setProgressVisible(true)
        binding!!.syncStatusCloud.visibility = View.GONE

        return binding!!.root
    }

    override fun onStart() {
        super.onStart()
        trySilentSignIn()
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
    }

    override fun onPause() {
        super.onPause()
        EventBus.getDefault().unregister(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(@Suppress("UNUSED_PARAMETER") event: RemoveCloudAccountDialogFragment.CanceledEvent) {
        setProgressVisible(false)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: RemoveCloudAccountDialogFragment.AccountRemovedEvent) {
        event.handle(requireContext())
        setProgressVisible(false)
        updateViews()
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    fun onEvent(event: SyncProgress.SyncEvent) {
        binding?.syncStatusCloud?.setProgress(event)
    }

    private fun trySilentSignIn() {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser != null) {
            changeAccount(firebaseUser, null)
            return
        }

        // check if the user is still signed in
        val signInTask = AuthUI.getInstance()
            .silentSignIn(requireContext(), HexagonTools.firebaseSignInProviders)
        if (signInTask.isSuccessful) {
            // If the user's cached credentials are valid, the OptionalPendingResult will be "done"
            // and the GoogleSignInResult will be available instantly.
            Timber.d("Got cached sign-in")
            handleSilentSignInResult(signInTask)
        } else {
            // If the user has not previously signed in on this device or the sign-in has expired,
            // this asynchronous branch will attempt to sign in the user silently.  Cross-device
            // single sign-on will occur in this branch.
            Timber.d("Trying async sign-in")
            signInTask.addOnCompleteListener { task ->
                if (isAdded) {
                    handleSilentSignInResult(task)
                }
            }
        }
    }

    /**
     * @param task A completed sign-in task.
     */
    private fun handleSilentSignInResult(task: Task<AuthResult>) {
        val account = if (task.isSuccessful) {
            task.result?.user
        } else {
            null
        }
        // Note: Do not show error message if silent sign-in fails, just update UI.
        changeAccount(account, null)
    }

    /**
     * If the Firebase account is not null, saves it and auto-starts setup if Cloud is not
     * enabled, yet. On sign-in failure disables Cloud.
     */
    private fun changeAccount(account: FirebaseUser?, errorIfNull: String?) {
        val signedIn = account != null
        if (signedIn) {
            Timber.i("Signed in with Google.")
            signInAccount = account
        } else {
            signInAccount = null
            hexagonTools.setDisabled()
            errorIfNull?.let {
                showSnackbar(getString(R.string.hexagon_signin_fail_format, it))
            }
        }

        setProgressVisible(false)
        updateViews()

        if (signedIn && Utils.hasAccessToX(context)
            && !HexagonSettings.isEnabled(context)) {
            // auto-start setup if sign in succeeded and Cloud can be, but is not enabled, yet
            Timber.i("Auto-start Cloud setup.")
            startHexagonSetup()
        }
    }

    private val signInWithFirebase =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                changeAccount(FirebaseAuth.getInstance().currentUser, null)
            } else {
                val response = IdpResponse.fromResultIntent(result.data)
                if (response == null) {
                    // user chose not to sign in or add account, show no error message
                    changeAccount(null, null)
                } else {
                    val errorMessage: String?
                    when (val errorCode = response.error?.errorCode ?: 0) {
                        ErrorCodes.NO_NETWORK -> {
                            errorMessage = getString(R.string.offline)
                        }
                        ErrorCodes.PLAY_SERVICES_UPDATE_CANCELLED -> {
                            // user cancelled, show no error message
                            errorMessage = null
                        }
                        else -> {
                            errorMessage = errorCode.toString()
                            Errors.logAndReport(
                                ACTION_SIGN_IN,
                                HexagonAuthError(ACTION_SIGN_IN, errorMessage)
                            )
                        }
                    }
                    changeAccount(null, errorMessage)
                }
            }
        }

    private fun signIn() {
        // Create and launch sign-in intent
        val intent = AuthUI.getInstance()
            .createSignInIntentBuilder()
            .setAvailableProviders(HexagonTools.firebaseSignInProviders)
            .build()

        signInWithFirebase.launch(intent)
    }

    private fun signOut() {
        setProgressVisible(true)
        AuthUI.getInstance().signOut(requireContext()).addOnCompleteListener {
            Timber.i("Signed out.")
            signInAccount = null
            hexagonTools.setDisabled()
            if (this@CloudSetupFragment.isAdded) {
                setProgressVisible(false)
                updateViews()
            }
        }
    }

    private fun updateViews() {
        // hexagon enabled and account looks fine?
        if (HexagonSettings.isEnabled(context)
            && !HexagonSettings.shouldValidateAccount(context)) {
            binding?.textViewCloudUser?.text = HexagonSettings.getAccountName(activity)
            binding?.textViewCloudDescription?.setText(R.string.hexagon_description)

            // enable sign-out
            binding?.buttonCloudAction?.setText(R.string.hexagon_signout)
            binding?.buttonCloudAction?.setOnClickListener { signOut() }
            // enable account removal
            binding?.buttonCloudRemoveAccount?.visibility = View.VISIBLE
        } else {
            // did try to setup, but failed?
            if (!HexagonSettings.hasCompletedSetup(activity)) {
                // show error message
                binding?.textViewCloudDescription?.setText(R.string.hexagon_setup_incomplete)
            } else {
                binding?.textViewCloudDescription?.setText(R.string.hexagon_description)
            }
            binding?.textViewCloudUser?.text = null

            // enable sign-in
            binding?.buttonCloudAction?.setText(R.string.hexagon_signin)
            binding?.buttonCloudAction?.setOnClickListener {
                // restrict access to supporters
                if (Utils.hasAccessToX(activity)) {
                    startHexagonSetup()
                } else {
                    Utils.advertiseSubscription(activity)
                }
            }
            // disable account removal
            binding?.buttonCloudRemoveAccount?.visibility = View.GONE
        }
    }

    /**
     * Disables buttons and shows a progress bar.
     */
    private fun setProgressVisible(isVisible: Boolean) {
        binding?.progressBarCloudAccount?.visibility = if (isVisible) View.VISIBLE else View.GONE

        binding?.buttonCloudAction?.isEnabled = !isVisible
        binding?.buttonCloudRemoveAccount?.isEnabled = !isVisible
    }

    private fun showSnackbar(message: CharSequence) {
        dismissSnackbar()
        snackbar = Snackbar.make(requireView(), message, Snackbar.LENGTH_INDEFINITE).also {
            it.show()
        }
    }

    private fun dismissSnackbar() {
        snackbar?.dismiss()
    }

    private fun startHexagonSetup() {
        dismissSnackbar()
        setProgressVisible(true)

        val signInAccountOrNull = signInAccount
        if (signInAccountOrNull == null) {
            signIn()
        } else {
            Timber.i("Setting up Hexagon...")

            // set setup incomplete flag
            HexagonSettings.setSetupIncomplete(context)

            // validate account data
            if (TextUtils.isEmpty(signInAccountOrNull.email)) {
                Timber.d("Setting up Hexagon...FAILURE_AUTH")
                // show setup incomplete message + error toast
                view?.let {
                    Snackbar.make(it, R.string.hexagon_setup_fail_auth, Snackbar.LENGTH_LONG)
                        .show()
                }
            }
            // at last reset sync state, store the new credentials and enable hexagon integration
            else if (hexagonTools.setEnabled(signInAccountOrNull)) {
                // schedule full sync
                Timber.d("Setting up Hexagon...SUCCESS_SYNC_REQUIRED")
                SgSyncAdapter.requestSyncFullImmediate(activity, false)
                HexagonSettings.setSetupCompleted(activity)
            } else {
                // Do not set completed, will show setup incomplete message.
                Timber.d("Setting up Hexagon...FAILURE")
            }

            setProgressVisible(false)
            updateViews()
        }
    }

    companion object {
        private const val ACTION_SIGN_IN = "sign-in"
    }
}
