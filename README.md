# Popi

Telefonda, Türkçe konuşarak ya da yazarak video ve marka içeriği üreten, kullanıcısını ve markasını hatırlayan **yerel öncelikli (local-first)** açık kaynak yaratıcı stüdyo.

> **Durum: erken araştırma.** Henüz kullanılabilir bir uygulama yok. Bu repoda şu an mimari kararlar, komut sözleşmesi ve ölçüm sonuçları duruyor. Kod, doğrulama kapılarını geçtikçe eklenecek.

## İlkeler
- **Tek komut motoru.** Manuel düzenleme ve yapay zekâ aynı doğrulanmış komutları çalıştırır ([komut şeması](docs/komut-semasi.md)). Model hata yapsa da editör çalışır, her şey geri alınabilir.
- **Yerel öncelikli.** Düzenleme, önizleme, dışa aktarma ve hafıza cihazda çalışır. Hesap, sunucu ve abonelik zorunlu değil.
- **Kendi anahtarını getir (BYOK).** Yapay zekâ özellikleri için bir model sağlayıcısı gerekir; kullanıcı kendi sağlayıcısını seçer. Anahtarlar cihazın güvenli deposunda tutulur. Sağlayıcıya ne gönderileceği önce gösterilir ve onaylanır.
- **Hafıza.** Marka, proje ve tercihler cihazda okunabilir dosyalarda saklanır.
- **Yapay zekâ kalıp içeriği (AI slop) yok.** Üretilen içerik kullanıcının gerçek görüntüsüne ve markasına dayanır; jenerik şablon üretmez.

## Hedef platformlar
Android, iOS ve masaüstü (Windows, macOS, Linux). Kotlin Multiplatform ile ortak çekirdek kullanılır. Video motoru her platformda yereldir: Android'de Media3, iOS'ta AVFoundation, masaüstünde FFmpeg. Hepsi tek bir `RendererPort` arayüzünün arkasındadır.

## Lisans
Apache-2.0

