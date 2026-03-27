# ChiselMod V2 – Real-Time Event Tracking System

A custom backend-driven event tracking system that integrates a server-side plugin with a web dashboard to provide real-time monitoring and data synchronization.

---

## 🚀 Overview

ChiselMod V2 is a Java-based backend plugin designed to capture live server events and sync them with a web application. The system enables real-time tracking, storage, and visualization of dynamic data through a full-stack architecture.

This project demonstrates:
- Event-driven architecture
- Backend-to-frontend data flow
- Real-time synchronization
- API-based communication

---

## 🧠 System Architecture

[ Server Plugin (Java) ]  
        ↓  
[ API Layer / Data Processing ]  
        ↓  
[ MySQL Database ]  
        ↓  
[ Web Dashboard (Frontend) ]

---

## ⚙️ How It Works

### 1. Event Capture (Backend Plugin)
- The plugin listens to server-side events  
- Events are processed and structured into usable data  
- Examples: user actions, system events, interactions  

### 2. Data Processing & API Layer
- Events are sent to a backend API layer  
- Data is formatted and prepared for storage  
- REST APIs are used for communication  

### 3. Database Storage
- All processed data is stored in a MySQL database  
- Ensures persistence and efficient querying  

### 4. Website Integration (Frontend)
- The web dashboard fetches data via APIs  
- Displays real-time or near real-time updates  
- Provides a user-friendly interface for monitoring  

---

## 🌐 Connected Web Platform

Frontend dashboard:  
👉 https://www.chiselcraft.online  

The website:
- Fetches backend data via APIs  
- Displays live updates  
- Acts as a visualization layer for system activity  

---

## 🛠️ Tech Stack

### Backend
- Java  
- REST APIs  

### Database
- MySQL  

### Frontend
- HTML, CSS, JavaScript  
- React  

### Tools
- Git & GitHub  
- Postman  
- VPS / Linux Hosting  

---

## 🔌 Core Features

### 📡 Real-Time Event Tracking
Captures server-side events instantly and processes them for storage and visualization on the web dashboard.

### 🔄 Backend–Frontend Synchronization
Seamless data flow between the backend system and the web application using REST APIs.

### 🗄️ Persistent Data Storage
All events and user data are stored in a MySQL database, ensuring reliability and historical tracking.

### 📊 Web Dashboard Integration
Live data is displayed on the connected website.

### ⚙️ Scalable Architecture
Designed with modular components (plugin → API → database → frontend) for easy expansion.

---

## ⚡ Event System (What It Tracks)

The system listens to and processes various events such as:

- User activity events (join/leave)  
- Interaction events (actions performed)  
- System-level triggers  
- Custom-defined events  

### Event Flow:
1. Event occurs in backend  
2. Plugin processes event  
3. Data stored in database / sent via API  
4. Frontend fetches data  
5. Dashboard updates dynamically  

---

## 🎮 Commands (If Configured)

> ⚠️ Commands depend on implementation

### 🔧 Admin Commands
- `/chisel reload` → Reloads configuration  
- `/chisel status` → Shows system/API status  
- `/chisel sync` → Forces manual data sync  

### 👤 User Commands
- `/stats` → View tracked data  
- `/profile` → View user information  

---

## ⚙️ Configuration

Example configuration:

```yml
database:
  host: localhost
  port: 3306
  name: chisel_db
  user: root
  password: your_password

api:
  endpoint: https://your-api-url.com
  enabled: true
