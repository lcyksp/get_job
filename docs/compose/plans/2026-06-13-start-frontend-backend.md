# Start Frontend and Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start both the frontend and backend servers for the Get Jobs project.

**Architecture:** The backend is a Spring Boot application started via a batch file, and the frontend is a Next.js application started via npm scripts. We need to install frontend dependencies first, then start both services.

**Tech Stack:** Java 21 (Spring Boot), Node.js (Next.js), Gradle, npm

---

## Task 1: Install Frontend Dependencies

**Covers:** [S1]
<!-- No spec section for this simple task -->

**Files:**
- Modify: `front/package.json` (already exists)

- [ ] **Step 1: Navigate to frontend directory and install dependencies**

Run in PowerShell:
```powershell
cd D:\get_jobs-main\front
npm install
```

- [ ] **Step 2: Verify installation completed successfully**

Run:
```powershell
Test-Path D:\get_jobs-main\front\node_modules
```
Expected: `True`

- [ ] **Step 3: No commit needed for dependency installation**

---

## Task 2: Start Backend Server

**Covers:** [S1]
<!-- No spec section for this simple task -->

**Files:**
- Read: `start-backend.bat` (already exists)
- Read: `backend.log` (will be created)

- [ ] **Step 1: Verify JAVA_HOME is set correctly**

Run:
```powershell
echo $env:JAVA_HOME
```
Expected: Should show Java 21 path or be empty (batch file sets it)

- [ ] **Step 2: Start backend server**

Run:
```powershell
cd D:\get_jobs-main
.\start-backend.bat
```

- [ ] **Step 3: Verify backend started successfully**

Wait 10 seconds, then check if backend.log exists and contains startup messages:
```powershell
Start-Sleep -Seconds 10
Test-Path D:\get_jobs-main\backend.log
Get-Content D:\get_jobs-main\backend.log -Tail 20
```

- [ ] **Step 4: No commit needed for server startup**

---

## Task 3: Start Frontend Server

**Covers:** [S1]
<!-- No spec section for this simple task -->

**Files:**
- Read: `front/start-dev.mjs` (already exists)
- Read: `front/server.config.js` (already exists)

- [ ] **Step 1: Start frontend development server**

Run:
```powershell
cd D:\get_jobs-main\front
npm run dev
```

- [ ] **Step 2: Verify frontend started successfully**

Wait 5 seconds, then check if server is listening on port 6866:
```powershell
Start-Sleep -Seconds 5
netstat -an | Select-String "6866"
```

- [ ] **Step 3: Test frontend accessibility**

Open browser to `http://127.0.0.1:6866` or use curl:
```powershell
curl http://127.0.0.1:6866
```

- [ ] **Step 4: No commit needed for server startup**

---

## Task 4: Verify Both Services Running

**Covers:** [S1]
<!-- No spec section for this simple task -->

**Files:**
- Read: `backend.log`
- Read: `front/` directory

- [ ] **Step 1: Check backend is running**

Run:
```powershell
Get-Process | Where-Object {$_.ProcessName -like "*java*"}
```

- [ ] **Step 2: Check frontend is running**

Run:
```powershell
Get-Process | Where-Object {$_.ProcessName -like "*node*"}
```

- [ ] **Step 3: Verify network ports**

Run:
```powershell
netstat -an | Select-String "8888|6866"
```
Expected: Should see both ports listening

- [ ] **Step 4: Summary of running services**

Both services should be running:
- Backend: http://localhost:8888
- Frontend: http://127.0.0.1:6866

- [ ] **Step 5: No commit needed for verification**

---

## Self-Review

1. **Spec coverage:** This is a simple startup task without a formal spec, so coverage is not applicable.

2. **Placeholder scan:** No placeholders found - all steps contain specific commands.

3. **Type consistency:** Commands are consistent across steps.

## Execution Handoff

Plan saved. How would you like to execute it?

Options:
- **Subagent, always**: Fresh subagent per task — remember for future sessions
- **Subagent, this time**: Fresh subagent per task — just this once  
- **Inline, always**: Execute in this session — remember for future sessions
- **Inline, this time**: Execute in this session — just this once