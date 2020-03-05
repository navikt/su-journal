package no.nav.su.journal

import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.httpPost
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import no.nav.su.person.sts.StsConsumer
import org.json.JSONObject
import java.lang.RuntimeException

internal sealed class DokArkiv {
    abstract fun opprettJournalpost(hendelse: String, corrrelationId: String): String
}

internal class DummyArkiv(): DokArkiv() {
    override fun opprettJournalpost(hendelse: String, corrrelationId: String): String = ""
}

internal class DokarkivClient(
    private val baseUrl: String,
    private val stsConsumer: StsConsumer
): DokArkiv() {
    override fun opprettJournalpost(hendelse: String, corrrelationId: String): String {
        val (_, _, result) = "$baseUrl/rest/journalpostapi/v1/journalpost".httpPost()
            .authentication().bearer(stsConsumer.token())
            .header(HttpHeaders.Accept, ContentType.Application.Json)
            .header(HttpHeaders.XCorrelationId, corrrelationId)
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
                              "fysiskDokument": "AAAAAAAA",
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
            ).responseString()

        return result.fold(
            { JSONObject(it).getString("journalpostId") },
            { throw RuntimeException("Feil i kallet mot journal") }
        )
    }
}