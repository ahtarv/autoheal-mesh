@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-23
set JAVA=%JAVA_HOME%\bin\java.exe
set JAR=out\autoheal-mesh.jar

if not exist %JAR% (
    echo [ERROR] JAR not found. Run build.bat first.
    exit /b 1
)

"%JAVA%" --enable-preview -jar %JAR%
endlocal
