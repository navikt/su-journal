package no.nav.su.journal

import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.httpPost
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders.Accept
import io.ktor.http.HttpHeaders.XCorrelationId
import no.nav.su.meldinger.kafka.soknad.NySøknadMedSkyggesak
import no.nav.su.meldinger.kafka.soknad.Personopplysninger
import no.nav.su.meldinger.kafka.soknad.SøknadInnhold
import no.nav.su.person.sts.StsConsumer
import org.json.JSONObject

val dokarkivPath = "/rest/journalpostapi/v1/journalpost"

internal sealed class DokArkiv {
    abstract fun opprettJournalpost(nySøknadMedSkyggesak: NySøknadMedSkyggesak, pdf: ByteArray): String
}

internal class DummyArkiv : DokArkiv() {
    override fun opprettJournalpost(nySøknadMedSkyggesak: NySøknadMedSkyggesak, pdf: ByteArray): String = ""
}

internal class DokarkivClient(
        private val baseUrl: String,
        private val stsConsumer: StsConsumer
) : DokArkiv() {
    override fun opprettJournalpost(nySøknadMedSkyggesak: NySøknadMedSkyggesak, pdf: ByteArray): String {
        val søknadInnhold = SøknadInnhold.fromJson(JSONObject(nySøknadMedSkyggesak.søknad))
        val (_, _, result) = "$baseUrl$dokarkivPath".httpPost()
                .authentication().bearer(stsConsumer.token())
                .header(Accept, ContentType.Application.Json)
                .header(XCorrelationId, nySøknadMedSkyggesak.correlationId)
                .body("""
                    {
                      "tittel": "Søknad om supplerende stønad for uføre flyktninger",
                      "journalpostType": "INNGAAENDE",
                      "tema": "SUP",
                      "behandlingstema": "ab0268",
                      "journalfoerendeEnhet": "9999",
                      "avsenderMottaker": {
                        "id": "${nySøknadMedSkyggesak.fnr}",
                        "idType": "FNR",
                        "navn": "${søkersNavn(søknadInnhold.personopplysninger)}"
                      },
                      "bruker": {
                        "id": "${nySøknadMedSkyggesak.fnr}",
                        "idType": "FNR"
                      },
                      "sak": {
                        "fagsakId": "${nySøknadMedSkyggesak.sakId}",
                        "fagsaksystem": "SUPSTONAD",
                        "sakstype": "FAGSAK"
                      },
                      "dokumenter": [
                        {
                          "tittel": "Søknad om supplerende stønad for uføre flyktninger",
                          "dokumentvarianter": [
                            {
                              "filtype": "PDFA",
                              "fysiskDokument": "${String(pdf)}",
                              "variantformat": "ARKIV"
                            },
                            {
                              "filtype": "JSON",
                              "fysiskDokument": ${nySøknadMedSkyggesak.søknad},
                              "variantformat": "ORIGINAL"
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent()
                ).responseString()

        return result.fold(
                { JSONObject(it).getString("journalpostId") },
                { throw RuntimeException("Feil i kallet mot journal") }
        )
    }

    private fun søkersNavn(personopplysninger: Personopplysninger): String = """${personopplysninger.etternavn}, ${personopplysninger.fornavn} ${personopplysninger.mellomnavn ?: ""}"""
}