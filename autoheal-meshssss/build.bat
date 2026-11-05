@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-23
set JAVAC=%JAVA_HOME%\bin\javac.exe
set JAVA=%JAVA_HOME%\bin\java.exe

set SRC=src\main\java
set TEST_SRC=src\test\java
set OUT=out\classes
set TEST_OUT=out\test-classes
set JAR=out\autoheal-mesh.jar

if exist out rmdir /s /q out
mkdir %OUT%
mkdir %TEST_OUT%
mkdir logs\bundles

echo [1/4] Compiling main sources...
"%JAVAC%" --release 23 --enable-preview ^
    -d %OUT% ^
    -sourcepath %SRC% ^
    %SRC%\com\autoheal\core\*.java ^
    %SRC%\com\autoheal\agent\*.java ^
    %SRC%\com\autoheal\detector\*.java ^
    %SRC%\com\autoheal\orchestrator\*.java ^
    %SRC%\com\autoheal\audit\*.java ^
    %SRC%\com\autoheal\escalation\*.java ^
    %SRC%\com\autoheal\AutoHealEngine.java ^
    %SRC%\com\autoheal\Main.java

if errorlevel 1 (
    echo [ERROR] Compilation failed.
    exit /b 1
)
echo [OK] Main sources compiled.

echo [2/4] Compiling test sources...
"%JAVAC%" --release 23 --enable-preview ^
    -d %TEST_OUT% ^
    -cp %OUT% ^
    %TEST_SRC%\com\autoheal\AutoHealMeshTest.java

if errorlevel 1 (
    echo [ERROR] Test compilation failed.
    exit /b 1
)
echo [OK] Test sources compiled.

echo [3/4] Packaging JAR...
"%JAVA_HOME%\bin\jar.exe" --create --file %JAR% ^
    --main-class com.autoheal.Main ^
    -C %OUT% .
echo [OK] JAR created: %JAR%

echo [4/4] Running tests...
"%JAVA%" --enable-preview -cp "%OUT%;%TEST_OUT%" com.autoheal.AutoHealMeshTest

if errorlevel 1 (
    echo [ERROR] Tests failed.
    exit /b 1
)

echo.
echo Build successful. Run: java --enable-preview -jar %JAR%
echo.
endlocal
