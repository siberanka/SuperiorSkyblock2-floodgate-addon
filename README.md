# SSBGeyser Addon

An advanced Paper addon plugin for **SuperiorSkyblock2** that dynamically translates all chest inventory GUI menus into native Bedrock/Floodgate forms for Geyser players.

---

## English Version

SSBGeyser dynamically intercepts inventory openings at the packet level, cancels chest GUI rendering on Bedrock client screens, and translates them into responsive, clean Bedrock Forms.

### Features
* **Dynamic GUI Translation**: Automatically converts any SuperiorSkyblock2 menu (upgrades, panel, biomes, etc.) without requiring manual per-menu YAML configuration.
* **Exploit & Dupe Proof**: Form click interactions are processed asynchronously on Geyser threads but securely simulated synchronously on the primary server thread.
* **Material & Skin Texture Mapping**: Converts Java materials to native Bedrock client textures and dynamically parses skull profiles to display custom player head skin textures via URL.
* **Smart Filtering**: Filler layout items (like stained glass panes) can be filtered out to optimize the Form size for mobile and console layouts.
* **Graceful Termination**: Instantly closes active Bedrock forms when the plugin disables or restarts, preventing client-side GUI locks and desyncs.
* **Bilingual Support**: Full configuration reload and localized message support for English and Turkish out-of-the-box.

### Commands & Permissions
* `/ssbgeyser reload` - Reloads configurations and language files. (Permission: `ssbgeyser.admin`)

### Requirements
* **Paper / Spigot**: 1.21.x
* **SuperiorSkyblock2**: 2026.1 / 26.1.x+
* **Floodgate 2.x** & **Geyser 2.x**

### How to Build
To build the plugin jar, clone the repository and run:
```bash
gradle build --no-daemon
```
If you encounter SSL handshake/PKIX path errors during dependency resolution (due to firewall or proxy SSL decryption), run:
```bash
gradle build --no-daemon "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
```
The compiled jar will be located under `build/libs/SSBGeyser-1.0.0.jar`.

---

## Türkçe Versiyon

Geyser üzerinden bağlanan Bedrock oyuncuları için tüm **SuperiorSkyblock2** sandık menülerini dinamik olarak yerel Bedrock/Floodgate formlarına dönüştüren gelişmiş bir Paper eklentisidir.

SSBGeyser, envanter açılışlarını paket düzeyinde dinamik olarak yakalar, Bedrock istemcisinde Java sandık arayüzünün açılmasını engeller ve bunları temiz, mobil uyumlu Bedrock Formlarına çevirir.

### Özellikler
* **Dinamik Arayüz Çevirisi**: Menü bazlı YAML ayarlarıyla uğraşmadan tüm SuperiorSkyblock2 menülerini (yükseltmeler, kontrol paneli, biyomlar vb.) otomatik olarak dönüştürür.
* **Açık ve Kopyalama (Dupe) Koruması**: Form tıklama etkileşimleri Geyser iş parçacıklarında işlenir ancak sunucunun ana iş parçacığında (primary thread) senkronize olarak güvenli bir şekilde simüle edilir.
* **Malzeme ve Kafa Derisi (Skin) Eşleştirme**: Java malzemelerini yerel Bedrock istemci dokularına dönüştürür ve özel oyuncu kafalarının doku URL'lerini yakalayarak form butonlarında gösterir.
* **Akıllı Filtreleme**: Boşluk doldurucu öğeler (renkli cam paneller vb.) gizlenerek form boyutları mobil ve konsol ekranları için optimize edilebilir.
* **Güvenli Kapatma**: Eklenti devre dışı kaldığında veya yeniden başlatıldığında, aktif Bedrock formlarını anında kapatarak oyuncuların arayüzlerinin kilitlenmesini ve senkronizasyon hatalarını önler.
* **Çift Dil Desteği**: Türkçe ve İngilizce dil paketleri ile tam uyumludur.

### Komutlar ve Yetkiler
* `/ssbgeyser reload` - Ayarları ve dil dosyalarını yeniden yükler. (Yetki: `ssbgeyser.admin`)

### Gereksinimler
* **Paper / Spigot**: 1.21.x
* **SuperiorSkyblock2**: 2026.1 / 26.1.x+
* **Floodgate 2.x** & **Geyser 2.x**

### Nasıl Derlenir (Build)
Eklentiyi derlemek için projeyi indirin ve şu komutu çalıştırın:
```bash
gradle build --no-daemon
```
Bağlantı esnasında SSL sertifikası (PKIX path building failed) hatası alırsanız, Windows sertifika deposunu kullanması için şu komutla derleyin:
```bash
gradle build --no-daemon "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
```
Derlenmiş jar dosyası `build/libs/SSBGeyser-1.0.0.jar` dizininde oluşacaktır.
