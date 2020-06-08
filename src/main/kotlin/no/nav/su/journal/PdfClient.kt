package no.nav.su.journal

import com.github.kittinunf.fuel.httpPost
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders.Accept
import io.ktor.http.HttpHeaders.XCorrelationId
import no.nav.su.meldinger.kafka.soknad.NySøknad

val suPdfGenPath = "/api/v1/genpdf/supdfgen/soknad"

internal class PdfClient(private val baseUrl: String) {
    fun genererPdf(nySøknad: NySøknad): ByteArray {
        val (_, _, result) = "$baseUrl$suPdfGenPath".httpPost()
            .header(Accept, ContentType.Application.Json)
            .header(XCorrelationId, nySøknad.correlationId)
            .body(nySøknad.søknad).response()

        return result.fold(
            { it },
            { throw RuntimeException("Feil i kallet mot journal") }
        )
    }
}