# BePTT 圖片網址黑名單 Xposed 模組

模組套件 `net.beptt.imagefilter` 獨立於 Play 商店的 BePTT。作用範圍固定為 `y_studio.ka5983.ptt2`。從模組的設定畫面管理完整網址，預設網址列在儲存庫根目錄的 [default-blocklist.txt](../default-blocklist.txt)。BePTT 的 APK、選單、簽章和設定檔不需改動。

## 安裝與設定

1. 使用已具備 libxposed API 101 或更新版與遠端設定服務的 Vector／LSPosed 環境。模組 APK 需要 Android 8.0 以上，框架本身另有系統與 root 環境要求。[Vector 安裝需求](https://github.com/JingMatrix/Vector#installation)
2. 安裝 `BePTT-Image-Filter-Xposed-1.0.2.apk`，在 Xposed 管理器啟用模組，確認作用範圍勾選 BePTT。
3. 打開獨立的「BePTT 圖片網址黑名單」App。可直接貼上網址，或選擇「匯入文字檔」，從手機／Dropbox 選一份 UTF-8 檔案。每行一個完整網址，按「儲存設定」才會套用。上限 64 KiB。
4. 首次啟用模組後強制停止並重開 BePTT。平常編輯名單後重新開啟文章即可。可匯出名單做備份，或從之前的 ReVanced 改版複製已儲存的網址。

名單由 Xposed 的遠端設定服務保存在獨立模組的設定群組。Google Play 更新 BePTT 後，重新啟動 App 即會針對新的 APK 找出圖片解析位置，名單繼續沿用。若目前手機上已有私人簽章的 BePTT，這個模組可用於支援的既有版本，但該安裝仍須先回到官方簽章才能恢復 Play 更新。切換前先備份舊資料。

## 過濾範圍

- 完整網址相同的圖片與連結卡片預覽會被過濾，同一網址出現在其他作者、本文或推文也適用。原始文字和可點選的文字連結保留。
- 名單只在本機讀取。Dropbox 是檔案來源之一，選檔時由 Android 檔案選擇器處理。模組無背景網址同步。
- 清空名單或關閉模組設定的核取方塊即可恢復預覽。沒有舊名單時會使用內建預設。
- 模組從 DEX 辨識圖片／網頁網址提取器、預覽資料類型和文章輸出方法，支援網頁與 Telnet 文章產生預覽的路徑。比對以提取前的完整網址為準，只有當預覽候選對應的來源都命中時才略過該預覽。
- 比對不到唯一目標或框架設定不可用時，保留原始行為。Xposed 日誌中的 `BePTT Image Filter` 可查看結構命中、首次實際過濾或初始化失敗。新版變更解析方式時可能需要更新模組。

## 建置與驗證

從儲存庫根目錄執行以下步驟。請先安裝 Python 3、JDK 17（或相容版本）、Android SDK Command-Line Tools、`curl` 和 `unzip`，並設定 `JAVA_HOME` 與 `ANDROID_HOME`。

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-36" "build-tools;36.0.0"

deps="$(mktemp -d)"
cp "$ANDROID_HOME/platforms/android-36/android.jar" "$deps/"
central=https://repo.maven.apache.org/maven2

for name in api service interface; do
  curl -fsSL "$central/io/github/libxposed/$name/102.0.0/$name-102.0.0.aar" \
    -o "$deps/$name.aar"
  unzip -p "$deps/$name.aar" classes.jar > "$deps/$name-102.0.0.jar"
done

for artifact in \
  org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar \
  com/google/guava/guava/27.1-android/guava-27.1-android.jar \
  com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar; do
  curl -fsSL "$central/$artifact" -o "$deps/${artifact##*/}"
done
```

`libxposed:api` 僅參與編譯，其餘五個程式庫會隨 APK 發行。準備自己的 Android 簽章金鑰，再執行建置。首次建置可用 `keytool -genkeypair -storetype PKCS12 -keystore <檔案路徑> -alias beptt-module -keyalg RSA -keysize 2048 -validity 10000` 互動式建立簽章金鑰。請另外準備一份第一行為金鑰庫密碼的文字檔，並將兩份檔案存放於儲存庫之外。自行建置的 APK 若使用不同金鑰，Android 會要求先移除既有模組才能安裝，建議事先從模組匯出設定。

```sh
build_dir="$(mktemp -d)"
python3 runtime-module/build_module.py \
  --deps "$deps" \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" \
  --java-home "$JAVA_HOME" \
  --work "$build_dir/work" \
  --out "$build_dir/BePTT-Image-Filter.apk" \
  --keystore /path/to/module-key.p12 \
  --password-file /path/to/keystore-password.txt \
  --alias beptt-module
```

建置程式驗證 APK 簽章與對齊。與先前的 ReVanced 版共用的網址規則位於 `patch/src/BepttUrlRules.java`，可獨立執行測試。

```sh
test_dir="$(mktemp -d)"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$test_dir" \
  patch/src/BepttUrlRules.java patch/tests/BepttUrlRulesTest.java
"$JAVA_HOME/bin/java" -cp "$test_dir" BepttUrlRulesTest
```

v1.0.2 的相容性測試涵蓋 BePTT 7.3.0 和 7.3.1 的原版 APK，以及缺少文章預覽結構的 APK。網址測試涵蓋完整查詢參數、同一圖片的其他尺寸以及其他影片網址。檔案匯入、較舊 Android 版本與後續 Play 覆蓋更新仍待實機驗收。
