# Mimari kararlar

Durum: 2026-10-11, ölçümlerle desteklenen ilk kararlar. Bir karar değişirse burada tarihle güncellenir.

## 1. Platform: Kotlin Multiplatform (KMP)
- **Ortak çekirdek (`commonMain`, saf Kotlin):** proje modeli, komut motoru, doğrulama, undo/redo, ajan (komut çevirisi), hafıza, model sağlayıcıları, ayarlar.
- **Arayüz:** Compose Multiplatform. Android, iOS ve masaüstü aynı ekran kodunu kullanır; platforma özgü küçük farklar `expect/actual` ile çözülür.
- **Video motoru platforma özeldir.** Hepsi tek bir `RendererPort` arayüzünün arkasındadır:
  | Platform | Önizleme | Dışa aktarma |
  |---|---|---|
  | Android | Media3 (ExoPlayer / CompositionPlayer) | Media3 Transformer |
  | iOS | AVPlayer + AVVideoComposition | AVAssetExportSession / AVAssetWriter |
  | Masaüstü (Windows, macOS, Linux) | FFmpeg (LGPL derlemesi) ile çözülen kareler | FFmpeg |
- **Neden Flutter ya da React Native değil?** Video motoru orada da yerel kalmak zorunda. Bu çerçeveler yalnızca bir dil ve bir köprü katmanı daha ekler.
- **iOS derlemesi Mac gerektirir.** Bu yüzden iOS hedefi baştan GitHub Actions'ın macOS makinesinde derlenir; ortak kodun iOS'ta bozulmadığı her commit'te görülür.
- **Yayın sırası:** Android → masaüstü → iOS. Mimari üçünü de baştan destekler.

## 2. İşlemci kullanımı
- **Video çözme ve kodlama:** donanım kodlayıcı (MediaCodec, VideoToolbox; masaüstünde FFmpeg'in donanım hızlandırması varsa o).
- **Görüntü işleme** (metin katmanı, 9:16'ya sığdırma): GPU. Media3 OpenGL ES ile, iOS Core Image/Metal ile yapar.
- **Mantık, ajan ve hafıza:** CPU, hafif işler.
- **Yerel konuşma tanıma:** CPU (ölçüm: `docs/olcumler.md`).

## 3. Optimizasyon (ölçerek, sırayla)
1. İçe aktarmada düşük çözünürlüklü önizleme kopyası (proxy, 540p) ve küçük resim önbelleği.
2. Dışa aktarma arka plan görevinde: Android'de WorkManager, iOS'ta BGTask.
3. Önizlemeyi değişikliğe göre güncellemek. Media3'te her düzenleme kompozisyonu baştan kuruyor (androidx/media #3204); gecikme ölçülüp gerekirse önbellek eklenir.
4. Isınma ve pil izleme. Kararlar gerçek cihaz ölçümüyle verilir; emülatör yalnızca işlev testidir.

## 4. Komut motoru
- Tek sözleşme: [`komut-semasi.md`](komut-semasi.md). Manuel arayüz ve yapay zekâ aynı komutları üretir.
- Motor her komutu doğrular. Geçersiz komut durumu değiştirmez; örneğin modelin ürettiği "60 sn'lik müziği 72 sn'ye uzat" komutu reddedilir.
- **v1 için ölçümden çıkan değişiklikler:**
  - `move_clip` için `toIndex` yerine `after: clipId | null` kullanılacak. Ölçüm: bir model `toIndex`'te iki kez bir sıra kaydı.
  - Ses klibine zaman çizelgesi başlangıcı (`startMs`) eklenecek. "Seslendirme selamdan sonra girsin" isteği şemada karşılık bulamadı; modeller bu durumda yanlışlıkla klibin başını kesti.
  - Video klibinin kendi sesi için `set_volume` desteği gelecek.
  - Metin silme komutu (`delete_text`) eklenecek.

## 5. Model sağlayıcıları (BYOK)
- Arayüz: `LLMProvider`, `ImageProvider`, `STTProvider`, `TTSProvider`.
- Anahtarlar cihazın güvenli deposunda tutulur (Android Keystore, iOS Keychain, masaüstünde işletim sisteminin kimlik deposu). Proje dosyalarına ve loglara yazılmaz.
- Kolay bağlantı: OpenRouter OAuth PKCE. Kullanıcı anahtar kopyalamaz.
- Varsayılan önerilerin ölçümleri `docs/olcumler.md` dosyasında.

## 6. Hafıza
- Cihazda, insan tarafından okunabilir markdown dosyaları: `marka/`, `projeler/`, `tercihler/`. Her kayıtta kaynak ve tarih tutulur ("siz söylediniz", "düzeltmenizden çıkardım").
- Hızlı arama için yanında SQLite indeksi.
- Ajana vault'un tamamı değil, yalnızca ilgili özet verilir. Kullanıcı her kaydı görebilir, silebilir ve dışa aktarabilir.
