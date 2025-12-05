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


## IDE management

- Extension Pack for Java from the Marketplace, Spring Initializr Java Support from the Marketplace, you may need to close are restart VSCode
    - This allows the Developer to run Java.Main and other files
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


## Database Management Workflow with Scripts

If you are working with the database, follow the below procedure to safely interact with the remote DB while applying changes locally. Certain scripts require spring to be running while others don't, so follow the instructions that the scripts provide.

Note, steps 1,2,3,5 are on your development (LOCAL) server. You need to update your .env on development server and be sure all PRs are completed, pulled, and tested before you start pushing to production.

1. Initialize your local DB with clean data. For example, this would be good to see that a schema update works correctly.
> python scripts/db_init.py

2. Pull the database content from the remote DB onto your local machine. This allows you to work with real data and test that real data works with your local changes.
> python scripts/db_prod2local.py

3. TEST TEST TEST! Make sure your changes work correctly with the local DB.

4. Now go onto the remote DB and back up the db using "cp sqlite.db backups/sqlite_year-month-day.db" in the volumes directory of the spring directory on cockpit. Then, run `git pull` to ensure that spring has been updated with the latest code. Then, run `python scripts/db_init.py` again to ensure that the remote DB schema is up to date with the latest code.

5. Once you are satisfied with your changes, push the local DB content to the remote DB. This requires authentication, so you need to replace the ADMIN_PASSWORD in the .env file of "spring" with the production admin password.
> python scripts/db_local2prod.py

## Condensed DB/Schema update simple steps

**(a copy of what's above, just condensed)**

1. Initialize local DB: `python scripts/db_init.py`

2. Pull production data to local: `python scripts/db_prod2local.py`

3. Test your changes locally

4. On production server (in cockpit):
- Backup DB in volumes directory: `cp sqlite.db backups/sqlite_year-month-day.db`
- Update code: `git pull`
- Update schema: `python scripts/db_init.py`

5. Push local changes to production: `python scripts/db_local2prod.py`
(Requires admin password from production in .env)