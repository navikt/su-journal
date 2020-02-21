package no.nav.su.journal

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo

const val STS_TOKEN = "ET_LANGT_TOKEN"

class StsStub {

   fun stubbedSTS() = WireMock.get(urlPathEqualTo("/rest/v1/sts/token"))
      .withBasicAuth(STS_USERNAME, STS_PASSWORD)
      .withHeader("Accept", equalTo("application/json"))
      .withQueryParam("grant_type", equalTo("client_credentials"))
      .withQueryParam("scope", equalTo("openid"))
      .willReturn(
      WireMock.okJson(
         """
{
    "access_token": "$STS_TOKEN",
    "expires_in": "3600"
}
""".trimIndent()
      )
   )
}
