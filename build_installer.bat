@echo off
REM ============================================================
REM  Dong goi VanVaPhu thanh file cai dat .exe cho Windows (jpackage)
REM
REM  YEU CAU TRUOC KHI CHAY FILE NAY:
REM   1. Da "Clean and Build" project trong NetBeans IT NHAT 1 LAN,
REM      de co du dist\VanVaPhu.jar va dist\lib\*.jar.
REM   2. Da cai JDK 21 va them vao PATH (jpackage co san trong JDK 21,
REM      khong can cai them gi rieng cho jpackage).
REM   3. QUAN TRONG: tren Windows, jpackage can WiX Toolset v3.x de tao
REM      duoc file .exe/.msi. Tai va cai tai: https://wixtoolset.org/releases/
REM      (chon ban v3.x, KHONG phai v4 - jpackage JDK 21 chua ho tro WiX v4),
REM      roi them thu muc cai dat WiX (co candle.exe, light.exe) vao PATH.
REM
REM  CHAY: double-click file nay (hoac chay tu Command Prompt trong thu
REM  muc goc project VanVaPhu).
REM ============================================================

setlocal
set APPNAME=GiamSatKhoLanh
set MAINJAR=VanVaPhu.jar
set MAINCLASS=coldstorage.server.MonitorServer
set VERSION=1.0

if not exist dist\%MAINJAR% (
    echo [LOI] Khong thay dist\%MAINJAR%.
    echo Hay mo project trong NetBeans va "Clean and Build" truoc, roi chay lai file nay.
    pause
    exit /b 1
)

where jpackage >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay lenh jpackage.
    echo Kiem tra da cai JDK 21 va da them thu muc jdk-21\bin vao bien moi truong PATH chua.
    pause
    exit /b 1
)

if exist installer rmdir /s /q installer

echo Dang dong goi, cho vai phut...
jpackage ^
  --type exe ^
  --name %APPNAME% ^
  --input dist ^
  --main-jar %MAINJAR% ^
  --main-class %MAINCLASS% ^
  --app-version %VERSION% ^
  --vendor "Do an VanVaPhu" ^
  --win-shortcut ^
  --win-menu ^
  --dest installer

if errorlevel 1 (
    echo.
    echo [LOI] jpackage that bai.
    echo Neu thong bao lien quan WiX/candle/light: cai WiX Toolset v3.x (xem huong dan o dau file nay) roi thu lai.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  XONG! File cai dat (.exe) nam trong thu muc: installer\
echo.
echo  LUU Y QUAN TRONG sau khi cai dat va chay thu:
echo  - App doc/ghi coldstorage.db va config.properties tai THU MUC
echo    LAM VIEC HIEN TAI (working directory), khong phai thu muc cai dat.
REM   - Neu cai vao Program Files (mac dinh), Windows co the CHAN ghi file
REM     do thieu quyen Admin - dau hieu la app mo len nhung khong luu duoc
REM     du lieu, hoac bao loi khi ghi coldstorage.db/config.properties/logs.
echo    Neu gap loi ghi file, thu cai dat vao 1 thu muc trong Documents/
echo    cua nguoi dung thay vi Program Files, hoac chay app voi quyen Admin.
echo  - Day la buoc dong goi CUOI CUNG, chi nen chay sau khi moi tinh nang
echo    da chay on dinh, vi moi lan sua code deu phai Clean and Build lai
echo    roi chay lai file nay.
echo ============================================================
pause
