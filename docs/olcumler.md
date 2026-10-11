# Ölçümler

Her sonuç tekrarlanabilir bir deneyden gelir. Tarih: 2026-10-11.

## Türkçe istek → komut çevirisi
Test seti, puanlayıcı ve başlangıç durumları `arastirma/komut-testi/` klasöründe.

**Kolay set (40 istek).** Göreli ifadeler ("biraz kıs" = ×0.6 gibi) istemde kural olarak verildi.
| Model | Doğru |
|---|---|
| Claude Haiku 5.5 | 40/40 |
| DeepSeek v4.1 Flash | 40/40 |
| Gemini 3.8 Flash | 40/40 |
| GPT-6 Luna | 40/40 |

**Zor set (34 istek).** İstemde kural yok. Konuşma dili, ses tanıma yazım hataları ("iki nci klibi üç saniye kıssalt"), zamir ve geçmişe atıf ("az önce kestiğim klibi geri getir"), şemanın yapamadığı istekler. Puanlama tek bir altın cevaba değil, son durumu sınayan koşullara bakar; farklı ama doğru çözümler de geçer.
| Model | Doğru | Hatalar |
|---|---|---|
| Claude Haiku 5.5 | 33/34 | Z12: hangi klibin kısaltılacağını sordu (savunulabilir) |
| Gemini 3.8 Flash | 33/34 | Z12: aynı |
| DeepSeek v4.1 Flash | 32/34 | Z12; müziği dosya süresinin ötesine uzatmaya çalıştı (motor reddeder) |
| GPT-6 Luna (medium) | 28/34 | `move_clip` sırasında iki kez bir kayma; giriş noktası ile zaman çizelgesi konumunu karıştırdı; müziği uzatmaya çalıştı |

**Sonuç:** Türkçe doğal dil anlama ana risk değil. Küçük ve ucuz modeller bile yetiyor. Hataların çoğu şema belirsizliğinden geliyor; düzeltmeleri `mimari.md` §4'te.

## Görsel üretimi (Nano Banana, Gemini görsel aracı)
- **Türkçe yazı:** 4 görselde ı, ş, İ ve "?" hatasız ("Ekşi Maya Nasıl Yapılır?").
- **İstenmemiş yazı:** logo görseline kendiliğinden "MAHALLE FIRINI & UNLU MAMULLER" ekledi.
- **Çözünürlük:** 9:16 görseller 768×1376. 1080×1920 video için büyütmek gerekir.
- **Referansla tutarlılık:** logo yeni sahneye aynen yerleştirildi; bu stil aktarımı değil, kompozisyon.
- **Süre:** görsel başına ortalama ~34 sn.
- **Karar:** arka planı model üretir, yazıyı uygulama katman olarak ekler (`icerik-ilkeleri.md`).

## Bekleyen ölçümler
- Yerel Türkçe ses tanıma (whisper.cpp, Vosk): kelime hata oranı, gecikme, bellek.
- Media3 ile uçtan uca dışa aktarma (emülatör, ardından gerçek cihaz).
