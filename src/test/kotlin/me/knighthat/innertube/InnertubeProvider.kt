package me.knighthat.innertube

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Test [Innertube.KtorProvider] backed by a real network client, used by the
 * live-network tests (e.g. [me.knighthat.innertube.response.InnertubeImplTest]).
 *
 * Mirrors the production client configuration in the app's `NetworkModule`
 * (OkHttp engine, lenient JSON content negotiation, gzip/deflate encoding) so
 * that requests behave the same as they do at runtime. Honours the environment
 * proxy when present so it also works inside sandboxed/CI runners.
 */
class InnertubeProvider : Innertube.KtorProvider {

    override val client: HttpClient = HttpClient( OkHttp ) {
        expectSuccess = true

        engine {
            ( System.getenv( "HTTPS_PROXY" ) ?: System.getenv( "https_proxy" ) )
                ?.takeIf( String::isNotBlank )
                ?.let { proxy = ProxyBuilder.http( Url( it ) ) }
        }

        install( ContentNegotiation ) {
            json( Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            } )
        }

        install( ContentEncoding ) {
            gzip( 1f )
            deflate( .1f )
        }
    }

    override val cookies: String = ""
    override val dataSyncId: String? = null
    override val visitorData: String = Constants.CHROME_WINDOWS_VISITOR_DATA
}
