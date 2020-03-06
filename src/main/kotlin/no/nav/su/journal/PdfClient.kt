package no.nav.su.journal

import com.github.kittinunf.fuel.httpPost
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders.Accept
import io.ktor.http.HttpHeaders.XCorrelationId
import no.nav.su.meldinger.kafka.soknad.NySøknadMedSkyggesak

val suPdfGenPath = "/api/v1/genpdf/supdfgen/soknad"

internal class PdfClient(private val baseUrl: String) {
    fun genererPdf(nySøknadMedSkyggesak: NySøknadMedSkyggesak, correlationId: String): ByteArray {
        val (_, _, result) = "$baseUrl$suPdfGenPath".httpPost()
            .header(Accept, ContentType.Application.Json)
            .header(XCorrelationId, correlationId)
            .body(nySøknadMedSkyggesak.søknad).response()

        return result.fold(
            { it },
            { throw RuntimeException("Feil i kallet mot journal") }
        )
    }
}