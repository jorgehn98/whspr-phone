@rem
@rem Gradle startup script for Windows
@rem
@echo off

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME:~0,-1%

if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
  set JAVA_EXE=java.exe
)

if not exist "%JAVA_EXE%" (
  where "%JAVA_EXE%" >NUL 2>NUL
  if errorlevel 1 (
    if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
      set JAVA_EXE=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe
    ) else if exist "%ProgramFiles%\Android\Android Studio\jre\bin\java.exe" (
      set JAVA_EXE=%ProgramFiles%\Android\Android Studio\jre\bin\java.exe
    ) else if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" (
      set JAVA_EXE=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe
    ) else (
      echo Java 17 no encontrado. Instala Android Studio/JDK o configura JAVA_HOME.
      exit /b 1
    )
  )
)

"%JAVA_EXE%" -Dorg.gradle.appname=%APP_BASE_NAME% -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
