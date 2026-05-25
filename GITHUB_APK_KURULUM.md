# Telefonda GitHub ile APK üretme

Bu projeye GitHub Actions dosyası eklendi. Telefonda büyük AndroidIDE kurmadan GitHub sunucusunda APK oluşturabilirsin.

## Kullanım

1. GitHub hesabına gir.
2. Yeni bir repository oluştur.
3. Bu zip dosyasını telefonda aç ve içindeki tüm dosyaları repository içine yükle.
4. Repository sayfasında **Actions** sekmesine gir.
5. **Android APK Build** iş akışını aç.
6. **Run workflow** butonuna bas.
7. İşlem bitince oluşan sayfada **Artifacts** bölümünden APK dosyasını indir.

APK adı genelde şu dosyadır:

`app-debug.apk`

## Not

Bu APK debug imzalıdır. Telefona kurup denemek için uygundur. Google Play'e yüklemek için ayrıca release imzası gerekir.
