package com.adhook.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class UpdateApkIdentity(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val currentSigners: Set<String>
)

/** Package/version/current-certificate metadata comparison, not cryptographic APK verification. The OS installer remains the signature gate. */
internal object UpdateApkVerifier {
    fun installed(context: Context): UpdateApkIdentity = identity(
        getPackageInfoCompat(context.packageManager, context.packageName)
    ).also {
        if (it.packageName != context.packageName) throw IOException("当前应用身份无法确认")
    }

    fun archive(context: Context, file: File): UpdateApkIdentity = identity(
        getPackageArchiveInfoCompat(context.packageManager, file.absolutePath)
            ?: throw IOException("安装包无法解析或证书元数据缺失")
    )

    private fun getPackageInfoCompat(pm: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun getPackageArchiveInfoCompat(pm: PackageManager, archiveFilePath: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                archiveFilePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(archiveFilePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun identity(info: PackageInfo): UpdateApkIdentity {
        val signatures = info.signingInfo?.apkContentsSigners
        if (signatures.isNullOrEmpty()) throw IOException("安装包签名信息缺失")
        val signers = signatures.map {
            val certificate = it.toByteArray()
            if (certificate.isEmpty()) throw IOException("安装包签名信息无效")
            hex(MessageDigest.getInstance("SHA-256").digest(certificate))
        }.toSet()
        return UpdateApkIdentity(info.packageName ?: throw IOException("安装包包名缺失"),
            info.versionName, info.longVersionCode, signers)
    }

    fun verify(candidate: UpdateApkIdentity, installed: UpdateApkIdentity, release: ReleaseInfo) {
        if (candidate.packageName != installed.packageName) throw IOException("安装包不是当前应用")
        if (candidate.currentSigners.isEmpty() || installed.currentSigners.isEmpty()
            || candidate.currentSigners != installed.currentSigners) throw IOException("安装包签名与当前应用不一致")
        // A signature rotation is a separate authorization decision; past certificates are not auto-trusted.
        if (candidate.versionCode <= installed.versionCode || installed.versionCode <= 0)
            throw IOException("安装包版本号没有递增")
        if (candidate.versionName != release.versionName) throw IOException("安装包版本与发布信息不一致")
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

/** Receipt for an owned completed download; install rechecks its bytes and current package identity. */
class DownloadedUpdate internal constructor(
    val file: File,
    internal val release: ReleaseInfo,
    internal val localSha256: String
) {
    val releaseTag: String get() = release.tagName
}

enum class UpdateInstallRequest { INSTALLER_OPENED, PERMISSION_REQUIRED }
