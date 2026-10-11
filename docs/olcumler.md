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
| MiMo v2.6 Pro (Evren) | 40/40 |

**Zor set (34 istek).** İstemde kural yok. Konuşma dili, ses tanıma yazım hataları ("iki nci klibi üç saniye kıssalt"), zamir ve geçmişe atıf ("az önce kestiğim klibi geri getir"), şemanın yapamadığı istekler. Puanlama tek bir altın cevaba değil, son durumu sınayan koşullara bakar; farklı ama doğru çözümler de geçer.
| Model | Doğru | Hatalar |
|---|---|---|
| Claude Haiku 5.5 | 33/34 | Z12: hangi klibin kısaltılacağını sordu (savunulabilir) |
| Gemini 3.8 Flash | 33/34 | Z12: aynı |
| MiMo v2.6 Pro (Evren) | 33/34 | Z12: aynı |
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

### Düşünme seviyesinin etkisi (eşitlenmiş tekrar)
İlk koşularda seviyeler eşit değildi: Luna medium, Gemini high, Haiku belirtilmemişti. High ile tekrar:
| Model | Zor set, ilk koşu | Zor set, high |
|---|---|---|
| GPT-6 Luna | 28/34 (medium) | **33/34** |
| Claude Haiku 5.5 | 33/34 (belirtilmemiş) | **34/34** |
Luna high'ın tek hatası yine `move_clip` sıra belirsizliği. **Ders:** model karşılaştırmasında seviye sabitlenip yazılmalı.

## Kapak yazısı: model kodla (SVG) mı, görsel model mi?
Aynı yazısız arka plan, aynı marka kuralları; her model yazı katmanını SVG olarak yazdı, headless Edge ile 1080×1920 çizildi. Dosyalar: `arastirma/svg-kapak/`.
| Model | SVG geçerli | 4 metin harfi harfine | Not |
|---|---|---|---|
| Haiku 5.5 | ✓ | ✓ | lacivert kutu + sarı hap |
| Haiku 5.5 high | ✓ | ✓* | *başlığı iki satıra bölerken satırlar arası boşluk kayboldu |
| Gemini 3.8 Flash high | ✓ | ✓ | lacivert kutu + sarı hap |
| GPT-6 Luna medium | ✗ | – | `&` XML kaçışı yok, SVG açılmıyor |
| GPT-6 Luna high | ✓ | ✓ | sol kenar çizgili kart (yapay zekâ kalıbı) |

**Sonuç:** kodla çizilen yazı Türkçe karakterde ve düzenlenebilirlikte görsel modelden açık ara iyi. Ama 5 çıktının 5'i de aynı "kutu + hap düğme" kalıbında; yan yana şablon gibi duruyor. Kalıptan kaçmak için görsel modele kasıtlı boşluk bıraktırmak ve tasarım modeline marka hafızasından gelen düzen dağarcığı vermek gerekiyor (sonraki deney).

## Yerel Türkçe ses tanıma → komut (uçtan uca)
Düzenleme komutu niteliğinde 20 cümle; iki sentetik ses (edge-tts Ahmet ve Emel), temiz ve ~15 dB gürültülü. Toplam 80 kayıt. Her motorun dökümü Claude Haiku 5.5 ile komuta çevrildi; son durum koşullarla puanlandı. Masaüstü CPU: Ryzen 5 5600.

| Motor | Boyut | Tepe RAM | RTF (masaüstü) | Kelime hata oranı | **Doğru komut** | Soru sordu / motor reddi | **Yanlış işlem** |
|---|---|---|---|---|---|---|---|
| whisper-large-v3-turbo q5 | 547 MB | 742 MB | 5.2 | %18.6 | 80/80 | 0 / 0 | 0 |
| whisper-small | 465 MB | 772 MB | 1.15 | %27.9 | 75/80 | 1 / 4 | 0 |
| whisper-base | 141 MB | 306 MB | 0.24 | %44.5 | 72/80 | 4 / 4 | 0 |
| whisper-tiny | 74 MB | 197 MB | 0.15 | %56.0 | 72/80 | 2 / 2 | 4 |
| vosk-small-tr | 56 MB | ~5 MB | 0.19 | %41.3 | 66/80 | 6 / 4 | 4 |
| *(hatasız metin)* | | | | %0 | 19/20 | | |

**Sonuçlar:**
- **Kelime hata oranı yanıltıcı.** "2. klibi 2 saniye kısalt" gibi doğru dökümler hata sayılıyor. Dil modeli bozuk dökümün büyük kısmını onarıyor.
- **Güvenli hata ile tehlikeli hata ayrı ölçülmeli.** Fiil düşünce model soru soruyor ya da motor geçersiz komutu reddediyor; bunlar kullanıcıya zarar vermez. Tehlikeli olan yanlış işlem: whisper-base ve üstünde sıfır.
- **Telefon için aday whisper-base** (141 MB; telefon işlemcisinde gerçek zamana yakın olacağı tahmin ediliyor, ölçülmedi). Güçlü cihazlarda whisper-small isteğe bağlı indirilebilir.
- **Android'in yerleşik çevrimdışı Türkçe tanıması güvenilir değil.** Bu, ölçüm değil kaynak taramasından çıkan bir sonuç; gerçek cihazda sınanacak.

**Sınırlar:** Sesler sentetik. Gerçek insan sesi (hızlı konuşma, yutulan heceler, ortam gürültüsü) sonuçları kötüleştirecek. Sıradaki ölçüm gerçek ses kayıtlarıyla yapılacak.

## Bekleyen ölçümler
- Gerçek insan sesiyle aynı uçtan uca test.
- Telefon işlemcisinde whisper-base gecikmesi (gerçek cihaz).
- Media3 ile uçtan uca dışa aktarma (emülatör, ardından gerçek cihaz).
