# BePTT 圖片網址黑名單

在官方 BePTT 裡隱藏指定網址的圖片和連結卡片預覽，保留文章文字和可點選的連結。這是獨立的 Xposed 模組，設定放在模組自己的 App。Google Play 更新 BePTT 後，原有名單會繼續沿用。模組會在下次啟動時重新辨識新版的圖片解析流程。

**[下載 v1.0.2 APK](https://github.com/29988122/beptt-image-filter/releases/download/v1.0.2/BePTT-Image-Filter-Xposed-1.0.2.apk)** · [版本說明與 SHA-256](https://github.com/29988122/beptt-image-filter/releases/tag/v1.0.2) · [預設排除網址](default-blocklist.txt)

## 安裝與使用

1. 手機需有能提供 libxposed API 101 以上及遠端設定服務的 Xposed 框架。本版實機驗證使用 Vector。[Vector 安裝文件](https://github.com/JingMatrix/Vector#installation)
2. 安裝模組 APK，開啟 Xposed 管理器，在「模組」啟用「BePTT 圖片網址黑名單」，並確認作用範圍勾選 BePTT。
3. 從手機 App 清單開啟「BePTT 圖片網址黑名單」。每行輸入一個完整網址，或透過 Android 檔案選擇器匯入 UTF-8 文字檔，然後按「儲存設定」。初次使用會帶入該 APK 版本的預設網址。要使用最新名單，可下載上方的清單並匯入。
4. 第一次啟用時強制停止並重開 BePTT。日後調整名單後，重新開啟文章即可。

比對的是文章中的完整網址，包含協定、大小寫、路徑與查詢參數。適用於圖片和 YouTube 等連結卡片。同一網址出現在本文、簽名檔或推文時都適用。模組可匯出名單做備份，或清空名單以顯示所有預覽。

## 相容性與除錯

v1.0.2 已在 Pixel 8 Pro、Android 17、BePTT 7.3.1 和 Vector 實測。命中的網址會保留文字連結。較舊 Android 與未來 Play 版本尚待實機驗證。

BePTT 更新後若改了預覽解析方式，模組在無法唯一辨識目標時會保留原有的預覽。遇到問題時，可在 Xposed 管理器的日誌中搜尋 `BePTT Image Filter`，並在回報時附上 Android、BePTT、框架與模組版本。

## 原始碼與授權

[建置與實作說明](runtime-module/README.md)包含所需工具、依賴與驗證方式。本專案採 [GPL-3.0](LICENSE)，隨 APK 散布的第三方程式庫各自適用其原授權。[第三方授權與聲明](THIRD-PARTY-NOTICES.txt)
