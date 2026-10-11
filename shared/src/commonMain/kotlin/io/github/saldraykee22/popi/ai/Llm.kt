package io.github.saldraykee22.popi.ai

/** Modele gönderilecek sistem ve kullanıcı metni. */
data class LlmRequest(val system: String, val user: String)

/**
 * Model sağlayıcısına senkron istek atan taşıma katmanı.
 * Arayüz bu çağrıyı arka plan iş parçacığında yapar.
 */
fun interface LlmTransport {
    fun complete(request: LlmRequest): String
}
