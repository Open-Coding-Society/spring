# Runtime links

Backend UI

Use login with .env setup user to manage and restore data.

- Runtime link: https://spring.opencodingsociety.com/

API access

Validate system is up by testing an endpoint

- Jokes endpoint: https://spring.opencodingsociety.com/api/jokes/

Examine JWT Login

Review cookies after accessing a page that needs them (ie Groups)

- JWT Login: https://pages.opencodingsociety.com/login

## Backend UI purpose

This Backend UI is to manage adminstrative functions like reseting passwords and managing database content: CRUD, Backup, and Restore.

- Thymeleaf UI should be visual and practical
- Home page is organized with Bootstrap menu and cards
- Most menus and operations are dedicated to Tables
- Some sample menus exist to reference basic capability

## Backend Primary purpose

The site is build on Springboot.  The project is primarly used to store and retrieve data through APIs.  The site has JWT authorization and implements security.  In optimal deployed form the data would be served through a professional database, it supports SQLite for development and deployment verification.

## Getting started

Java 21 or higher is requirement using VSCode tooling.

- Install Java 21: **macOS** `brew install --cask temurin@21` | **Linux** `sudo apt install openjdk-21-jdk`
- Clone project, open in VSCode
- Run `Main.java` (if issues: `Ctrl+Shift+P` → "Java: Reload Projects")
- Browse to http://127.0.0.1:8585/

**Build Commands:**
```bash
./mvnw clean compile    # Build
./mvnw test            # Test  
./mvnw spring-boot:run # Run
```

**Key Files:** Java source (`src/main/java/...`) | templates and application.properties (`src/main/resources/templates/...`)

### Configuration Requirements

- Create custom `.env` file to setup default user passwords to satisfy code in Person.java.  Students of OCS should leave users as default until competency is obtained.

```java
final String adminPassword = dotenv.get("ADMIN_PASSWORD");
final String defaultPassword = dotenv.get("DEFAULT_PASSWORD");
```

- Modify `application.properties` ports to be unique for your indivdual project.

```text
server.port=8585
socket.port=8589
```

## Run Project

- Play or click entry point is Main.java, look for Run option in code.  This eanbles Springboot to build and load.
    - If you do not see the `Run | Debug` option in code, install the **Java Extension Pack** (by Microsoft) and **Spring Boot Extension Pack** (by VMware)
- Load loopback:port in browser (http://127.0.0.1:8585/)
- Login to ADMIN (toby) user using ADMIN_PASSWORD, examing menus and data
- Try API endpoint: http://127.0.0.1:8585/api/jokes/

## Database migration (backup, reset, import)

Common flows you can run from the project root:

1) Reset to clean schema + default data (fast)

```bash
scripts/db_init.sh
```

2) Migrate from deployed server (fetch all tables, then import)

```bash
# No prompts
FORCE_YES=true scripts/db_migrate.sh --import

# With prompts
scripts/db_migrate.sh --import
```

3) Export current local data to JSON (admin endpoint)

```bash
scripts/export_current_json.sh
# → volumes/backups/exports_YYYYMMDD_HHMMSS.json
```

4) Preserve Users/Groups/Bathroom across updates (export → reset → selective import)

```bash
scripts/db_preserve.sh
# Default preserves person, groups, tinkle

# Customize tables to restore
IMPORT_TABLES=person,groups,tinkle,bathroom_queue scripts/db_preserve.sh
```

5) Import from a JSON file

```bash
# Import ALL tables from a JSON file
python3 scripts/import_json_to_sqlite.py volumes/data.json volumes/sqlite.db

# Explicitly import ALL
IMPORT_TABLES=ALL python3 scripts/import_json_to_sqlite.py volumes/backups/exports_*.json volumes/sqlite.db

# Import only selected tables
IMPORT_TABLES=person,groups,tinkle python3 scripts/import_json_to_sqlite.py volumes/backups/exports_*.json volumes/sqlite.db
```

Notes:
- The importer auto-matches JSON keys to actual DB columns and JSON-serializes nested dict/list values.
- If port 8585 is busy, stop the app before running migration scripts.

## IDE management

- Extension Pack for Java from the Marketplace, you may need to close are restart VSCode
- A ".gitignore" can teach a Developer a lot about Java runtime.  A target directory is created when you press play button, byte code is generated and files are moved into this location.
- "pom.xml" file can teach you a lot about Java dependencies.  This is similar to "requirements.txt" file in Python.  It manages packages and dependencies.

## .env files

- In order to run this project locally, a .env file should be set up with the appropriate variables:
- GAMIFY_API_URL=
- GAMIFY_API_KEY= 
- ADMIN_PASSWORD=123Toby!
- DEFAULT_PASSWORD=123Qwerty!

## Person MVC

![Class Diagram](https://github.com/user-attachments/assets/26219a16-e3dc-45e3-af1c-466763957dce)

- Basically there is a rough MVCframework.
- The webpages act as the view. These pages can view details about the users, and request the controller to change details about them
- The controller is mainly "personViewController" for the backend, but other controllers include "personApiController" for the front end.
- Techincally the image is wrong, "personDetailsService" is a controller. It is used by other controllers to change the database, so it seemed more accurate to call it a part of the model, rather than a controller.
- The person.java is the pojo (object) that is used for the database schema.
