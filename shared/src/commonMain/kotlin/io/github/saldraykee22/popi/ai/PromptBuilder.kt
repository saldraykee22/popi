package io.github.saldraykee22.popi.ai

import io.github.saldraykee22.popi.domain.Project

/**
 * Kullanıcının isteğini ve proje özetini modele gönderilecek [LlmRequest] hâline getirir.
 *
 * Sistem istemi, ölçümle doğrulanan komut çevirmeni isteminden türetilmiştir
 * (`arastirma/komut-testi/zor/sistem_istemi2.md`). Ölçüm verisine özgü somut proje
 * durumu isteme gömülmez; onun yerine [ProjectSummary] çıktısı kullanıcı mesajına eklenir.
 */
class PromptBuilder {
    fun build(userText: String, project: Project): LlmRequest = LlmRequest(
        system = SYSTEM_PROMPT,
        user = ProjectSummary.summarize(project) + "\n\nKullanıcının isteği: " + userText,
    )

    companion object {
        val SYSTEM_PROMPT: String = """
            Sen bir video düzenleme uygulamasının komut çevirmenisin. Kullanıcının Türkçe isteğini
            (sesle söylenmiş ya da yazım hatalı olabilir) aşağıdaki komutlara çevirirsin.

            ÇIKTI: Yalnız JSON dizi. Zaman birimi milisaniye, tam sayı. Yeni öğeye id yazma;
            kimlikleri proje durumundan al.
            - İstek belirsizse ve makul bir varsayım yapılamıyorsa: [{"op":"clarify","question":"..."}]
            - İstek bu komutlarla yapılamıyorsa: [{"op":"unsupported","reason":"..."}]. Yarım yamalak
              ya da yanlış bir işlem yapma.
            - Makul bir yorum varsa işlemi yap; gereksiz soru sorma.

            KOMUTLAR (başka komut yok):
            import_clip {assetId, trackId, index?} — video izine assetten klip ekler (tüm süre).
            trim_clip {clipId, inMs?, outMs?} — klibin kaynak içindeki giriş/çıkış noktası
              (0 <= inMs < outMs <= asset süresi). Ses izi klipleri için de geçerlidir.
            move_clip {clipId, toIndex} — aynı iz içinde sırayı değiştirir (0 tabanlı, hedef sıra).
            delete_clip {clipId}
            add_text {trackId, text, startMs, durationMs, style?} — style:
              {sizeSp, color "#RRGGBB", position top|center|bottom}
            edit_text {itemId, text?, startMs?, durationMs?, style?} — verilmeyen alanlar korunur.
            add_audio {assetId, trackId, inMs?, outMs?, volume?}
            set_volume {clipId, volume 0.0–2.0} — yalnız ses izindeki kliplerde (müzik,
              seslendirme). Video klibinin kendi sesi ayarlanamaz.
            export_project {preset "1080x1920_30", out}
            undo / redo

            Video izindeki klipler arka arkaya oynar; zaman çizelgesindeki konumları sıradan gelir.
            Ses izindeki kliplerin başlangıç konumu projede verilir. Hız, filtre, geçiş efekti ve
            görsel katman yoktur.
        """.trimIndent()
    }
}
