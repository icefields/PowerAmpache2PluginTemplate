package luci.sixsixsix.powerampache2.plugin

import android.content.Context
import android.content.Intent

const val PACKAGE_NAME_FDROID_DEBUG = "luci.sixsixsix.powerampache2.fdroid.debug"
const val PACKAGE_NAME_FDROID = "luci.sixsixsix.powerampache2.fdroid"

const val PACKAGE_NAME_GITHUB_DEBUG = "luci.sixsixsix.powerampache2.debug"
const val PACKAGE_NAME_GITHUB = "luci.sixsixsix.powerampache2"

const val PACKAGE_NAME_PLAY_DEBUG = "luci.sixsixsix.powerampache2.play.debug"
const val PACKAGE_NAME_PLAY = "luci.sixsixsix.powerampache2.play"

const val PACKAGE_NAME_DOGMAZIC_DEBUG = "luci.sixsixsix.powerampache2.free.debug"
const val PACKAGE_NAME_DOGMAZIC = "luci.sixsixsix.powerampache2.free"


fun Context.openPowerAmpache2() {
    val packagesArray = listOf(
        PACKAGE_NAME_FDROID,
        PACKAGE_NAME_GITHUB,
        PACKAGE_NAME_PLAY,
        PACKAGE_NAME_DOGMAZIC,
        PACKAGE_NAME_PLAY_DEBUG,
        PACKAGE_NAME_GITHUB_DEBUG,
        PACKAGE_NAME_FDROID_DEBUG,
        PACKAGE_NAME_DOGMAZIC_DEBUG
    )

    for (packageName in packagesArray) {
        packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("launched_by_auto", true)
        }?.let { startActivity(it); return }
    }

    /**
     * TODO:lucifer PA2
     * override fun onCreate(savedInstanceState: Bundle?) {
     *     super.onCreate(savedInstanceState)
     *
     *     if (intent?.getBooleanExtra("launched_by_auto", false) == true) {
     *         launch notification to keep pa2 alive
     *     }
     * }
     */
}
