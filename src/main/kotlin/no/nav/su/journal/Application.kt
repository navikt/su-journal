package no.nav.su.journal

import io.ktor.application.Application
import io.ktor.config.ApplicationConfig
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.client.CollectorRegistry
import no.nav.su.person.sts.StsConsumer

@KtorExperimentalAPI
internal fun Application.sujournal() {
    val collectorRegistry = CollectorRegistry.defaultRegistry
    installMetrics(collectorRegistry)
    naisRoutes(collectorRegistry)
    SøknadConsumer(environment.config, velgArkiv()).lesHendelser()
}

private fun Application.velgArkiv(): DokArkiv = when {
    fromEnvironment("dokarkiv.skarp") == "true" -> DokarkivClient(
        stsConsumer = StsConsumer(
            baseUrl = fromEnvironment("sts.url"),
            username = fromEnvironment("sts.username"),
            password = fromEnvironment("sts.password")
        ),
        baseUrl = fromEnvironment("dokarkiv.url")
    )
    else -> DummyArkiv()
}

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

@KtorExperimentalAPI
internal fun ApplicationConfig.getProperty(key: String): String = property(key).getString()

@KtorExperimentalAPI
fun Application.fromEnvironment(path: String): String = environment.config.property(path).getString()