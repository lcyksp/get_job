@echo off
set JAVA_HOME=D:\java21
set PATH=D:\java21\bin;%PATH%
cd /d D:\get_jobs-main
call gradlew.bat bootRun > D:\get_jobs-main\backend.log 2>&1
