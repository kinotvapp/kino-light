package android.net

import java.net.URI

/**
 * A real [Uri] for JVM unit tests. android.jar's is a stub and the unit tests run with
 * `isReturnDefaultValues`, so `Uri.parse` returns null there and nothing that builds a media3
 * `DataSpec` can open a connection. Declared in `android.net` only because [Uri]'s constructor is
 * package-private. Backed by [java.net.URI]; covers what media3's OkHttp data source reads
 * (`toString`), plus the obvious accessors.
 */
class JvmUri(private val value: String) : Uri() {
    private val uri = URI(value)
    override fun buildUpon(): Builder = throw UnsupportedOperationException("JvmUri is read-only")
    override fun getAuthority(): String? = uri.authority
    override fun getEncodedAuthority(): String? = uri.rawAuthority
    override fun getEncodedFragment(): String? = uri.rawFragment
    override fun getEncodedPath(): String? = uri.rawPath
    override fun getEncodedQuery(): String? = uri.rawQuery
    override fun getEncodedSchemeSpecificPart(): String? = uri.rawSchemeSpecificPart
    override fun getEncodedUserInfo(): String? = uri.rawUserInfo
    override fun getFragment(): String? = uri.fragment
    override fun getHost(): String? = uri.host
    override fun getLastPathSegment(): String? = pathSegments.lastOrNull()
    override fun getPath(): String? = uri.path
    override fun getPathSegments(): List<String> = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    override fun getPort(): Int = uri.port
    override fun getQuery(): String? = uri.query
    override fun getScheme(): String? = uri.scheme
    override fun getSchemeSpecificPart(): String? = uri.schemeSpecificPart
    override fun getUserInfo(): String? = uri.userInfo
    override fun isHierarchical(): Boolean = !uri.isOpaque
    override fun isRelative(): Boolean = !uri.isAbsolute
    override fun toString(): String = value
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) = throw UnsupportedOperationException("JvmUri is not parcelable")
}
