package no.nav.su.journal

import io.ktor.server.testing.withTestApplication
import io.ktor.util.KtorExperimentalAPI
import no.nav.su.journal.KafkaConfigBuilder.Topics.SOKNAD_TOPIC
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test

@KtorExperimentalAPI
class JournalComponentTest {

    @Test
    fun `når vi får en melding om en søknad som har blitt tilordnet en skyggeskak i Gsak, men ikke journalført, så skal vi journalføre søknaden`(){
        withTestApplication({
            testEnv()
            sujournal()
        }) {
            val kafkaConfig = KafkaConfigBuilder(environment.config)
            val producer = KafkaProducer(kafkaConfig.producerConfig(), StringSerializer(), StringSerializer())
            producer.send(ProducerRecord(SOKNAD_TOPIC,"""
                {
                    "soknadId":"",
                    "sakId":"",
                    "soknad":""
                }
            """.trimIndent()))
            Thread.sleep(2000)
        }
    }
}