package io.github.saldraykee22.popi.ai

import io.github.saldraykee22.popi.domain.CommandEngine
import io.github.saldraykee22.popi.domain.DomainError
import io.github.saldraykee22.popi.domain.Project

/** Kullanıcı isteğinin modele gitmeden önceki ve sonraki hâlini taşıyan öneri. */
sealed interface Proposal {
    /**
     * Uygulanabilir komutlar. [before] ve [after] kopya motor üzerinde dry-run ile üretilir;
     * gerçek proje hiçbir zaman değişmez.
     */
    data class Ready(val commandsJson: String, val before: Project, val after: Project) : Proposal

    /** Model ek bilgi istedi. */
    data class NeedsClarification(val question: String) : Proposal

    /** İstek desteklenmiyor ya da motor komutu reddetti. */
    data class Rejected(val reason: String) : Proposal

    /** Ağ ya da ayrıştırma hatası. */
    data class Failed(val detail: String) : Proposal
}

/**
 * Yazıyla düzenleme çekirdeği: kullanıcı isteğini modele gönderilecek isteğe çevirir,
 * yanıtı çözümler ve komutları kopya bir motorda dry-run eder.
 *
 * [propose] gerçek proje durumunu değiştirmez; doğrulama [CommandEngine] kopyasında yapılır.
 */
class AiEditor(
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val parser: ResponseParser = ResponseParser(),
) {
    /** Onay ekranında gösterilecek ve modele gönderilecek içerik: birebir aynıdır. */
    fun outgoing(userText: String, project: Project): LlmRequest = promptBuilder.build(userText, project)

    fun propose(userText: String, project: Project, transport: LlmTransport): Proposal {
        val response = try {
            transport.complete(outgoing(userText, project))
        } catch (e: Exception) {
            return Proposal.Failed(e.message ?: e.toString())
        }
        return when (val parsed = parser.parse(response)) {
            is ParsedResponse.Commands -> dryRun(parsed.json, project)
            is ParsedResponse.Clarify -> Proposal.NeedsClarification(parsed.question)
            is ParsedResponse.Unsupported -> Proposal.Rejected(parsed.reason)
            is ParsedResponse.Invalid -> Proposal.Failed(parsed.detail)
        }
    }

    private fun dryRun(commandsJson: String, project: Project): Proposal {
        val engine = CommandEngine(project)
        try {
            engine.executeJson(commandsJson)
        } catch (e: DomainError.ClarificationRequired) {
            return Proposal.NeedsClarification(e.message ?: "")
        } catch (e: DomainError) {
            return Proposal.Rejected(e.message ?: e.toString())
        }
        return Proposal.Ready(commandsJson, project, engine.project)
    }
}
