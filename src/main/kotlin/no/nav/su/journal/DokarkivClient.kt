package no.nav.su.journal

import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.json.responseJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders.Accept
import io.ktor.http.HttpHeaders.XCorrelationId
import no.nav.su.person.sts.StsConsumer

val dokarkivPath = "/rest/journalpostapi/v1/journalpost"

internal sealed class DokArkiv {
    abstract fun opprettJournalpost(hendelse: String, pdf: ByteArray, correlationId: String): String
}

internal class DummyArkiv: DokArkiv() {
    override fun opprettJournalpost(hendelse: String, pdf: ByteArray, correlationId: String): String = ""
}

internal class DokarkivClient(
    private val baseUrl: String,
    private val stsConsumer: StsConsumer
): DokArkiv() {
    override fun opprettJournalpost(hendelse: String, pdf: ByteArray, correlationId: String): String {
        val (_, _, result) = "$baseUrl$dokarkivPath".httpPost()
            .authentication().bearer(stsConsumer.token())
            .header(Accept, ContentType.Application.Json)
            .header(XCorrelationId, correlationId)
            .body(
                """
                    {
                      "avsenderMottaker": {
                        "id": 999263550,
                        "idType": "ORGNR",
                        "land": "Norge",
                        "navn": "NAV"
                      },
                      "behandlingstema": "ab0001",
                      "bruker": {
                        "id": 12345678910,
                        "idType": "FNR"
                      },
                      "datoMottatt": "2019-11-29",
                      "dokumenter": [
                        {
                          "brevkode": "NAV 14-05.09",
                          "dokumentKategori": "SOK",
                          "dokumentvarianter": [
                            {
                              "filnavn": "eksempeldokument.pdf",
                              "filtype": "PDFA",
                              "fysiskDokument": "${String(pdf)}",
                              "variantformat": "ARKIV"
                            }
                          ],
                          "tittel": "Søknad om foreldrepenger ved fødsel"
                        }
                      ],
                      "eksternReferanseId": "string",
                      "journalfoerendeEnhet": 9999,
                      "journalpostType": "INNGAAENDE",
                      "kanal": "NAV_NO",
                      "sak": {
                        "arkivsaksnummer": 111111111,
                        "arkivsaksystem": "GSAK",
                        "fagsakId": 111111111,
                        "fagsaksystem": "FS38",
                        "sakstype": "FAGSAK"
                      },
                      "tema": "FOR",
                      "tilleggsopplysninger": [
                        {
                          "nokkel": "bucid",
                          "verdi": "eksempel_verdi_123"
                        }
                      ],
                      "tittel": "Ettersendelse til søknad om foreldrepenger"
                    }
         """.trimIndent()
            ).responseJson()

        return result.fold(
            { it.obj().getString("journalpostId") },
            { throw RuntimeException("Feil i kallet mot journal") }
        )
    }
}