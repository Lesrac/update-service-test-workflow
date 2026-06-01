@echo off
REM Installation script for CDR Client Update Service
REM Run this script as Administrator after installing the main CDR Client

echo Installing CDR Client Update Service...

REM Register event log source
echo Registering event log source...
powershell -ExecutionPolicy Bypass -Command "if (![System.Diagnostics.EventLog]::SourceExists('CDRClientUpdateService')) { [System.Diagnostics.EventLog]::CreateEventSource('CDRClientUpdateService', 'Application'); Write-Host 'Event log source registered successfully' } else { Write-Host 'Event log source already exists' }"

if %ERRORLEVEL% NEQ 0 (
    echo Warning: Could not register event log source. Service will still work but may not log to Event Viewer.
)

REM Install the service
echo Installing service...
sc create CDRClientUpdateService binPath= "%~dp0CdrClientUpdateService.exe" start= auto DisplayName= "curaLINE Client Update Service"

if %ERRORLEVEL% NEQ 0 (
    echo Failed to install service!
    exit /b %ERRORLEVEL%
)

REM Set service description
sc description CDRClientUpdateService "Automatically manages updates for curaLINE Client on Windows Server (advanced installation)"

REM Set service to restart on failure
sc failure CDRClientUpdateService reset= 86400 actions= restart/60000/restart/60000/restart/60000

REM Start the service
echo Starting service...
sc start CDRClientUpdateService

if %ERRORLEVEL% NEQ 0 (
    echo Warning: Service installed but failed to start. You may need to start it manually.
) else (
    echo Service started successfully!
)

echo.
echo Installation complete!
echo curaLINE Client updateservice is now running.
echo Check for updates interval can be configured in appsettings.json (UpdateCheckIntervalHours).
echo.
pause

