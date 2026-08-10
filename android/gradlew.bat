@rem [TASK-D1-02] Gradle Wrapper Windows 启动脚本
@rem 小趴菜儿童端 — Windows 构建入口
@if "%DEBUG%" == "" @echo off
@rem ##########################################################################

@rem 设置 Gradle 启动参数
set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem 查找 Java
if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto execute
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute
goto fail

:execute
@rem 设置 JVM 参数
set DEFAULT_JVM_OPTS="-Xmx2048m" "-Dfile.encoding=UTF-8"
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem 执行 Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem 正常结束

:fail
echo ERROR: JAVA_HOME is not set and no 'java' command could be found.
echo Please set the JAVA_HOME variable to match the location of your Java installation.
exit /b 1
