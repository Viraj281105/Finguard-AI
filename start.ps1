# FinGuard AI - Start All Services
Write-Host "Starting FinGuard AI..." -ForegroundColor Green

# Start Docker (PostgreSQL)
Write-Host "Starting PostgreSQL..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd 'D:\Programming Codes\Projects\Full_Stack_Project\Finguard-AI'; docker-compose up"

# Wait for postgres to be ready
Write-Host "Waiting for PostgreSQL to start..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

# Start Backend
Write-Host "Starting Spring Boot backend..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd 'D:\Programming Codes\Projects\Full_Stack_Project\Finguard-AI\backend'; .\mvnw spring-boot:run"

# Start Frontend
Write-Host "Starting React frontend..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd 'D:\Programming Codes\Projects\Full_Stack_Project\Finguard-AI\frontend'; npm run dev"

Write-Host ""
Write-Host "All services starting!" -ForegroundColor Green
Write-Host "Frontend: http://localhost:5173" -ForegroundColor Cyan
Write-Host "Backend:  http://localhost:8080" -ForegroundColor Cyan
Write-Host "Database: localhost:5432" -ForegroundColor Cyan