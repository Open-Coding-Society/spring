package com.open.spring.mvc.capstone;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Component
@Configuration
public class CapstoneInit {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CapstoneProjectRepository repository;

    @Bean
    CommandLineRunner initCapstone() {
        return args -> {
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                st.execute(buildCapstoneProjectsTableSql(conn));
                st.execute(buildCollectionTableSql(conn, "capstone_tech", "tech"));
                st.execute(buildCollectionTableSql(conn, "capstone_team_members", "team_members"));
                st.execute(buildCollectionTableSql(conn, "capstone_key_points", "key_points"));
                st.execute(buildCollectionTableSql(conn, "capstone_impact", "impact"));
            } catch (SQLException e) {
                System.err.println("Failed to create capstone tables: " + e.getMessage());
                return;
            }
            seedProjects();
        };
    }

    private void seedProjects() {
        LocalDateTime now = LocalDateTime.now();
        List<CapstoneProject> projects = buildProjects();
        for (CapstoneProject p : projects) {
            repository.findByTitle(p.getTitle()).ifPresentOrElse(existing -> {
                existing.setSubtitle(p.getSubtitle());
                existing.setDescription(p.getDescription());
                existing.setAbout(p.getAbout());
                existing.setCourseCode(p.getCourseCode());
                existing.setStatus(p.getStatus());
                existing.setTech(p.getTech());
                existing.setTeamMembers(p.getTeamMembers());
                existing.setKeyPoints(p.getKeyPoints());
                existing.setImpact(p.getImpact());
                existing.setPageUrl(p.getPageUrl());
                existing.setFrontendUrl(p.getFrontendUrl());
                existing.setBackendUrl(p.getBackendUrl());
                repository.save(existing);
            }, () -> {
                p.setCreatedAt(now);
                repository.save(p);
            });
        }
        System.out.println("Capstone projects seeded/updated (" + projects.size() + ")");
    }

    private List<CapstoneProject> buildProjects() {
        List<CapstoneProject> list = new ArrayList<>();

        list.add(p(
            "Slack Messaging Platform",
            "Recreating Slack with Spring Boot + Flask Socket.IO",
            "A full-stack Slack-style messaging platform with real-time channels, message threading, AI-powered task extraction, and admin moderation — deployed to messaging.opencodingsociety.com.",
            "A full-stack Slack-style messaging platform targeting messaging.opencodingsociety.com. The frontend Jekyll site provides a channel sidebar, message timeline, and composer wired to Spring REST APIs via shared auth cookies. Flask Socket.IO handles low-latency live message fanout while Spring Boot remains the source of truth for persistence and permissions. Message threading, AI task extraction via Claude API, and admin moderation round out the feature set.",
            "CSA", "In Development",
            ls("Spring Boot 3.5 / Java 21", "Flask Socket.IO", "Jekyll + Static JS", "Claude API (Anthropic)", "Amazon S3 / Aurora", "JWT Cookie Auth", "GitHub Actions CI/CD"),
            ls("Anvay Vahia", "Mihir Bapat", "Yash Parikh"),
            ls("Create/join channels with real-time Socket.IO message delivery",
               "Reply-to-message threading with contextual inline previews",
               "AI-powered task extraction from channel conversations (Claude API)",
               "Admin moderation: delete messages, lock channels, manage roles",
               "JWT cookie auth across Spring backend and Flask Socket.IO layer",
               "Deployed to messaging.opencodingsociety.com"),
            ls("Users send and receive messages in real-time channels",
               "Threaded replies preserve conversation context",
               "AI auto-extracts and tracks action items per user",
               "Admins moderate content and manage channel permissions",
               "Platform deployed and accessible at messaging.opencodingsociety.com"),
            "https://messaging.opencodingsociety.com", null, null,
            "/images/capstone/database_defenders.png"
        ));

        list.add(p(
            "Educators",
            "Navigating Time in Code: From Errors to Understanding",
            "An educational platform that helps CS newcomers build mental models for temporal problem-solving in software development.",
            "An educational platform that helps CS newcomers build mental models for temporal problem-solving in software development. Through interactive lessons, learners explore how to trace make errors back to their source, understand the timeline of merge conflicts, and develop systematic debugging approaches. Each lesson uses temporal wayfinding principles — helping students understand not just what went wrong, but when and why, building crucial skills for navigating codebases and version control systems with confidence.",
            "CSA", "In Development",
            ls("GitHub Pages", "Interactive Tutorials", "JavaScript/React", "Git Workflows", "VSCode Integration", "Markdown Lessons", "Progress Analytics"),
            ls("Nithika Vivek", "Eshika Pallpotu", "Saanvi Dogra"),
            ls("Interactive lessons for make error resolution strategies",
               "Guided merge conflict walkthroughs with branching scenarios",
               "Step-by-step debugging workflows with visual timelines",
               "GitHub workflow tutorials for version control mastery",
               "Real-world code navigation and temporal reasoning exercises",
               "Progress tracking and skill assessment framework"),
            ls("95% of learners successfully resolve merge conflicts independently",
               "Average debugging time reduced by 60% after module completion",
               "100% improvement in make error diagnosis accuracy",
               "Students report 4x confidence increase in Git workflows",
               "Reduces onboarding time for new developers by 40%"),
            "https://pages.opencodingsociety.com/capstone/educators/",
            "https://github.com/NithikaVivek/pages-educators",
            "https://github.com/NithikaVivek/spring-educators",
            "/images/capstone/educators_icon.png"
        ));

        list.add(p(
            "Hunger Heroes",
            "Fighting Food Insecurity with Technology",
            "A community-driven platform connecting food donors with local shelters, food banks, and families in need, using graph algorithms for optimal routing.",
            "A community-driven platform connecting restaurants, grocery stores, and individuals with excess fresh food to local shelters, food banks, and families in need. Features intelligent graph algorithms for optimal location matching, real-time donor-receiver pairing, and time-sensitive notifications to ensure food reaches those in need before it spoils. Supports UN Sustainable Development Goal 2: Zero Hunger.",
            "CSA", "In Development",
            ls("Python Flask Backend", "Spring Boot Java Backend", "JavaScript Frontend", "RESTful API Design", "Graph Data Structures", "SQLite/PostgreSQL Database"),
            ls("Ahaan", "Shaurya", "Arnav"),
            ls("Real-time food donation matching system",
               "Graph-based proximity algorithm for optimal routing",
               "Python Flask REST API backend",
               "JavaScript interactive frontend",
               "SQLite/PostgreSQL database integration",
               "Real-time notifications for time-sensitive donations"),
            ls("Reduces food waste in communities",
               "Connects donors with those in need",
               "Supports SDG 2: Zero Hunger",
               "Real-time matching and notifications"),
            "https://pages.opencodingsociety.com/capstone/hunger-heroes/",
            "https://github.com/Ahaanv19/hunger_heroes",
            "https://github.com/Ahaanv19/hunger_heroes_backend"
        ));

        list.add(p(
            "Quantitative Trading Bot",
            "ML + News Sentiment for short-term market prediction",
            "A quantitative trading bot that predicts short-term stock movement using machine learning and real-time financial news sentiment, following an iterative DBR development process.",
            "A quantitative trading bot that predicts short-term stock movement using market indicators and real-time financial news sentiment. The project follows a DBR approach with repeated build-test-refine cycles to improve performance. The bot will also power an interactive trading experience through integration with Fortune Finders and Wand, allowing users to test predictions and strategies in a game-like environment.",
            "CSA", "In Progress",
            ls("Python", "Machine Learning", "NLP Sentiment", "Web Scraping", "SQL Database", "Graphs", "Backtesting", "Alpaca API (planned)", "Interactive Game Integration"),
            ls("Anvay", "Sai", "Aashray"),
            ls("Combines price data with real-time news sentiment",
               "Built using iterative Design-Based Research (DBR) cycles",
               "Uses ML + feature engineering for prediction",
               "Backtesting + paper trading planned for evaluation",
               "Integrated with Fortune Finders and Wand to create an interactive trading game"),
            ls("Makes professional-style quant workflows accessible to students",
               "Creates a structured dataset of price/news/sentiment/predictions",
               "Supports model evaluation through backtesting",
               "Enables an interactive trading simulation through Fortune Finders and Wand",
               "Designed for deployment through OCS capstone pages"),
            null, null, null
        ));

        list.add(p(
            "Bud-E",
            "Gamifying Focus With a Growing Virtual Companion",
            "A browser extension that gamifies productivity through a persistent virtual pet that grows when users stay focused and degrades when they navigate to distracting sites.",
            "Bud-E is a browser extension that gamifies productivity through a persistent virtual pet. Students configure whitelisted productive websites and the pet grows in real-time while they stay focused. Clicking away to distracting sites causes the pet to degrade visually. Unlike punitive blockers, Bud-E provides positive reinforcement through an emotionally-engaging character. The system progresses through distinct visual stages (seed to tree), creating satisfying visual feedback for sustained focus while maintaining low anxiety.",
            "CSA", "Research & Testing Phase",
            ls("Manifest V3 Browser Extension", "Content Script for Widget Injection", "Background Service Worker for Tab Monitoring", "Chrome Storage API for Persistence", "CSS Animations for Growth/Degradation Transitions", "Cross-browser compatible (Chrome, Firefox, Edge)", "Figma for Prototyping", "User Interview Research (n=8-10)"),
            ls("Aadi Bhat", "Pranav Santhosh", "Nolan Hightower"),
            ls("Persistent virtual pet widget visible on all pages",
               "Real-time growth when on whitelisted productive sites",
               "Real-time degradation when navigating to distracting sites",
               "Visual evolution through distinct stages (seed to sprout to plant to flower to tree)",
               "Customizable website whitelist and blacklist",
               "Local storage of pet progress and settings",
               "Growth statistics and session tracking",
               "Positive reinforcement without guilt or anxiety"),
            ls("Positive, game-like approach to productivity without punishment",
               "Visual accountability and intrinsic motivation through progress systems",
               "30%+ increase in time spent on whitelisted sites",
               "60%+ daily active usage rate after 2 weeks",
               "40%+ user retention at 30 days",
               "Low anxiety/stress around the mechanic",
               "Users understand progress without checking stats",
               "Emotional attachment to bud-e through design"),
            null, null, null
        ));

        list.add(p(
            "Granolaa",
            "Local-First Monitoring Without Cloud Dependencies",
            "A local monitoring application that streams live screen and webcam feeds over local HTTP URLs, viewable in any browser without cloud infrastructure.",
            "Granolaa is a local monitoring application that streams live screen and webcam feeds over local HTTP URLs, viewable in any browser without cloud infrastructure, accounts, or external services. Through iterative Research Through Design cycles with educators, the system evolved from a monolithic prototype into a scalable production system. Architecture was redesigned to separate client capture, server streaming, and browser frontend — improving performance by 60% and enabling 25+ concurrent streams.",
            "CSA", "In Development / Testing",
            ls("Java Client (Maven, JavaCV/OpenCV)", "Node.js Backend (WebSocket Manager)", "Browser-based Web Interface", "MJPEG Streaming over HTTP", "Stream Multiplexing & Quality Adjustment", "Auto-reconnect Protocol", "Cross-platform compatible"),
            ls("Aadi Bhat", "Pranav Santhosh", "Nolan Hightower"),
            ls("Live screen capture (MJPEG) over local HTTP",
               "Live webcam feed streaming without cloud infrastructure",
               "Lightweight Java application with Maven packaging",
               "Browser-accessible endpoints for same-LAN devices",
               "Multi-device stream support for simultaneous monitoring",
               "Scalable 3-tier architecture (client/server/frontend)",
               "Tested with 25+ concurrent client streams",
               "Auto-reconnect for reliable network handling"),
            ls("Lightweight alternative to heavyweight proctoring tools",
               "Transparent and locally-inspectable monitoring solution",
               "60% performance improvement through architecture redesign",
               "Reliable scaling to 25+ concurrent monitored streams",
               "30% improvement in browser performance per client",
               "Acceptable quality with minimal bandwidth (500 Kbps-1.2 Mbps per stream)",
               "Validated through real classroom deployments",
               "Strong foundation for recording and playback features"),
            null, null, null
        ));

        list.add(p(
            "Wayfinding Pages",
            "Making Collaboration Visible in CS Education",
            "A platform that transforms social collaboration from subjective evaluation into measurable, visible signals for team formation and persona-based matching.",
            "A system that transforms social collaboration from subjective evaluation into measurable, visible signals. Tracks student interactions across GitHub Issues, PRs, and comments to surface active navigators and reliable collaborators. Uses persona-based matching (organizer, debugger, communicator, builder) to form balanced teams that encourage diverse working styles. Students can evolve their personas over time, creating a visible record of how collaboration styles adapt and improve throughout the semester.",
            "CSA", "In Development",
            ls("Spring Boot Backend", "RESTful API Design", "GitHub API Integration", "React/JavaScript Frontend", "PostgreSQL Database", "Team Formation Algorithms"),
            ls("Ruta", "Vibha", "Risha"),
            ls("GitHub-integrated social analytics tracking",
               "Persona-based team formation algorithm",
               "Spring Boot backend with REST API",
               "Interactive collaboration dashboard",
               "Dynamic persona evolution over sprints",
               "Real-time team health signals and metrics"),
            ls("Makes collaboration measurable, not subjective",
               "Encourages intentional teamwork patterns",
               "Surfaces social learning as a core CS skill",
               "Reduces teacher guesswork in team dynamics",
               "Helps students self-diagnose team health"),
            "https://pages.opencodingsociety.com/capstone/wayfinding/",
            "https://github.com/blackstar3092/wayfinding_pages",
            "https://github.com/blackstar3092/wayfinding_spring"
        ));

        list.add(p(
            "AutoTriage",
            "Making Issue Tracking Feel Like a Teammate, Not a Chore",
            "A GitHub-native dev companion that builds issues for you, keeps your team aligned, and gives teachers a 30-second pulse on every group — without feeling like surveillance.",
            "A system that transforms GitHub issue management from a tedious overhead task into an intelligent, student-friendly workflow. A Chrome extension watches what files students are touching and proactively suggests issue tracking in context. When students hit Generate, Claude builds a fully-formatted issue — user story, DBR design rationale, acceptance criteria, and labels — based on what they actually built. A soft skills scorer privately evaluates issue clarity before posting, offering nudges rather than grades. Teachers access everything through a single /pulse Slack command.",
            "CSA", "In Development",
            ls("Chrome Extension (Activity Watcher + Buddy Overlay)", "Claude API Integration", "GitHub App with Webhook Listener", "Issue Engine with Activity Ingestor", "Slack Bot (/pulse Command)", "Issue-Commit Alignment Mapper"),
            ls("Neil", "Shriya", "Nikhil"),
            ls("Live coding buddy overlay with context-aware issue prompts",
               "One-click SCRUM-formatted issue generation from real activity",
               "Claude-powered soft skills scorer that nudges, never grades",
               "Issue-to-commit alignment that surfaces drift before standup",
               "/pulse Slack command for 30-second team health snapshots",
               "Student-first privacy: scores are private, nothing posts without consent"),
            ls("Reduces friction in writing well-structured GitHub issues",
               "Teaches SCRUM and DBR formatting through guided generation",
               "Gives instructors a real-time pulse without invasive monitoring",
               "Helps students self-correct drift between issues and commits",
               "Builds professional dev habits in a classroom-safe environment"),
            null, null, null
        ));

        list.add(p(
            "Oasis",
            "Building Communities One Connection at a Time",
            "A web-based community building game designed in partnership with the San Diego Oasis non-profit, centering on growing individual relationships as the foundation for community.",
            "A web-based community building game designed in partnership with the San Diego Oasis non-profit. The game centers on growing individual relationships as the foundation for creating a vibrant community. Players engage with various community members, developing connections through meaningful interactions and shared experiences. As individual relationships strengthen, the broader community flourishes, reflecting the real-world impact of personal connections. The game aligns with Oasis's mission of promoting healthy aging through lifelong learning and community engagement.",
            "CSA", "In Development",
            ls("JavaScript/HTML5 Game Engine", "Web-based Platform (Browser Compatible)", "Responsive Design for Accessibility", "Database for Relationship Tracking", "Progressive Web App (PWA) Support", "User Authentication and Progress Saving"),
            ls("Spencer", "Nora"),
            ls("Interactive community building game focused on relationship growth",
               "Individual relationship tracking and development mechanics",
               "Integration with San Diego Oasis non-profit mission",
               "Progressive community building from individual connections",
               "Engaging gameplay designed for older adult learners",
               "Web-based platform accessible to all community members"),
            ls("Promotes social connection among older adults",
               "Teaches community building principles through gameplay",
               "Supports San Diego Oasis's healthy aging mission",
               "Provides accessible digital engagement for diverse users",
               "Creates meaningful learning experiences through play"),
            "https://pages.opencodingsociety.com/capstone/oasis/",
            "https://github.com/Frogpants/community-backend",
            "https://github.com/Frogpants/community-backend"
        ));

        list.add(p(
            "Kora Capstone",
            "Autonomous Property Maintenance, Powered by AI",
            "An AI-native property maintenance operating system that automates tenant requests, triages problems, matches vendors, and keeps operations moving without manual coordination.",
            "Kora is an AI-native platform designed to automate the full maintenance lifecycle in property management. It transforms unstructured tenant requests, photos, messages, and invoices into structured maintenance actions. Using intelligent triage, vendor matching, SMS dispatch, and workflow orchestration, Kora reduces the need for manual coordination while improving speed, visibility, and operational consistency. Built as an operations backbone rather than just a dashboard, Kora helps property managers and institutional owners scale maintenance workflows efficiently across portfolios.",
            "CSA", "In Development",
            ls("TypeScript", "NestJS API", "Next.js Frontend", "PostgreSQL + Prisma", "BullMQ + Redis", "OpenAI Integration", "Twilio SMS", "Event-Driven Architecture"),
            ls("Manas", "Akshay"),
            ls("AI-powered triage for maintenance requests using text and image inputs",
               "Smart vendor matching based on trade, ZIP code, reliability, and response time",
               "Automated SMS dispatch with natural-language response parsing",
               "End-to-end maintenance workflow automation from intake to completion",
               "Real-time dashboards for tracking jobs, SLAs, and portfolio activity",
               "Property-level isolation for secure multi-tenant operations"),
            ls("Reduces maintenance coordination overhead through automation",
               "Speeds up issue resolution with AI-driven triage and dispatch",
               "Improves vendor response efficiency and job matching accuracy",
               "Enables 24/7 operations without depending on manual intervention",
               "Supports scalable, enterprise-grade property management workflows"),
            null, null, null
        ));

        list.add(p(
            "Pirna",
            "Algorithm-Driven Interactive Messaging for OCS",
            "A full-stack messaging system for OCS that uses advanced data structures and algorithms to enable real-time group communication with gamification and analytics.",
            "Student collaboration on OCS historically occurred off-platform, reducing visibility into group participation. To resolve this, a robust full-stack messaging application is being engineered using Object-Oriented Programming, Single Responsibility Principle, and advanced data structures (Stacks, Queues, Trees, Graphs). The system features real-time chat, gamification analytics, encrypted interactions, and a bathroom pass queue — transitioning from a frontend prototype to a scalable educational platform.",
            "CSA", "Active Full-Stack Development",
            ls("WebSockets (Real-Time)", "Hashing & Encryption", "Trees, Graphs & Machine Learning", "Stacks, Queues & Sorting Algorithms", "OOP & Design Patterns"),
            ls("Nikhil Maturi", "Rohan Bojja", "Adiya Katre"),
            ls("Real-Time Groups Chat (WebSockets)",
               "Bathroom Pass & Student Management (Queues)",
               "Undo/Redo Message Functionality (Stacks)",
               "Chat Summarization & Classification (Trees/ML)",
               "User Connections & Recommendations (Graphs)",
               "Secure Message & Image Encryption (Hashing)"),
            ls("Seamless Real-Time Educational Collaboration",
               "Efficient Bathroom Pass Queue Tracking",
               "Gamification Analytics to Track User Engagement",
               "Intelligent Message Organization & Sorting",
               "Centralized Calendar Management",
               "Scalable, Refactored Backend Architecture"),
            "https://pages.opencodingsociety.com/capstone/pirna/",
            "https://github.com/adikatre/Pirna-pages",
            "https://github.com/adikatre/Pirna-spring"
        ));

        list.add(p(
            "NodCursor",
            "Head-Driven Cursor Control Without Installs or Cloud",
            "A browser-based accessibility tool that replaces the mouse with head movement and facial gestures, powered by MediaPipe — no installs, no cloud, no data transmitted.",
            "NodCursor is a browser-based accessibility tool that replaces the mouse with head movement and facial gestures. Powered by MediaPipe Tasks Vision, it tracks facial landmarks in real time and maps nose position and brow tilt to cursor coordinates. A Web Worker handles exponential smoothing and gesture signal derivation off the main thread, keeping the UI fluid. Gesture controls — blink, double-blink, long-blink — trigger click and scroll actions without any hardware beyond a webcam. The entire stack runs locally in the browser with no accounts, no server, and no data transmission.",
            "CSA", "In Development / Testing",
            ls("React + TypeScript + Vite", "MediaPipe Tasks Vision (FaceLandmarker)", "Web Worker (trackingWorker.ts)", "Exponential Smoothing Pipeline", "Tailwind CSS", "Calibration Viewport Mapping", "Gesture Dispatch (useGestureControls)", "useFaceTracking / useCursorMapping Hooks"),
            ls("Aadi Bhat", "Pranav Santhosh"),
            ls("Browser-native head tracking via MediaPipe FaceLandmarker",
               "Facial gestures mapped to click, scroll, and drag events",
               "Web Worker pipeline for real-time smoothing with minimal latency",
               "Calibration system mapping head range to viewport coordinates",
               "Exponential smoothing with deadzone and acceleration curve",
               "Blink, double-blink, and long-blink gesture detection",
               "React + TypeScript + Vite, entirely client-side",
               "Privacy-first: no data leaves the browser"),
            ls("Fully browser-native — no app install required",
               "Privacy-first: all inference runs locally on-device",
               "Accessible cursor control for motor-impaired users",
               "Gesture-based click and scroll without physical peripherals",
               "Smooth tracking across varied lighting and camera setups",
               "Deadzone and acceleration tuning for fatigue-free use",
               "Calibration system adapts to each user's motion range",
               "Foundation for gaze, voice, and switch-access extensions"),
            "https://pages.opencodingsociety.com/capstone/nodcursor/",
            "https://github.com/aadibhat09/NodCursor",
            null
        ));

        list.add(p(
            "College Bound",
            "Giving Students What They Need",
            "A student-centered platform providing a comprehensive college preparation guide with a live College Explorer powered by the U.S. College Scorecard API.",
            "College Bound is a student-centered platform that now includes a live College Explorer experience. Students can discover schools, compare outcomes, and evaluate academic and financial fit using official U.S. Department of Education College Scorecard data. The platform collects past presentations, scholarship opportunities, and school contact information — providing a one-stop guide for navigating high school toward college.",
            "CSA", "In Development",
            ls("Jekyll", "HTML / CSS", "JavaScript", "Spring Boot (Java)", "College Scorecard API"),
            ls("Xavier Thompson", "Aranya Bhattacharya", "Trevor Vick"),
            ls("Collection of past College Bound presentations",
               "Access to various scholarship & academic opportunities",
               "Information and ways to contact school officials",
               "College Explorer with live search by name, state, type, and net price",
               "Side-by-side college comparison with student fit indicators",
               "API-only live data from U.S. College Scorecard (no CSV dependency)",
               "Race and sex distribution donut charts in the college detail view"),
            ls("Gives students clearer college planning with real admissions and cost data",
               "Helps families evaluate affordability and outcomes in one place",
               "Improves transparency through visual demographic insights and easy comparison"),
            "https://pages.opencodingsociety.com/capstone/college-bound/",
            "https://github.com/collegeboundacademy/college-bound",
            "https://github.com/collegeboundacademy/college-bound-backend"
        ));

        list.add(p(
            "HawkHub",
            "Empowering Student Leaders Through Effective Club Management",
            "A comprehensive club management platform that consolidates member management, event scheduling, attendance tracking, and leadership development in one accessible interface.",
            "HawkHub is a comprehensive club management platform designed to help student leaders organize, manage, and grow their clubs. Through iterative design research with student officers and club advisors, the system was built to address pain points in club coordination including scattered information, difficulty tracking engagement, and challenges maintaining member communication. Key features enable automatic notifications, attendance analytics, and role-based access control to support transparent leadership development.",
            "CSA", "Active Development",
            ls("JWT Authentication", "RESTful API Architecture", "Responsive UI/UX", "Sorting Feature", "Club Recommendation System", "Real-time Notifications"),
            ls("Avika Prasad", "Soni Dhenuva", "Samhita Lagisetti"),
            ls("Centralized club information and meeting management",
               "Member engagement and attendance tracking",
               "Event scheduling and notifications",
               "Leadership role and admin assignment",
               "Chat system for member communication",
               "Mobile-responsive design for accessibility"),
            ls("Centralized platform and easy to read hub for displaying clubs",
               "Allows students to easily find clubs and events that interest them",
               "Clubs can showcase their events and activities to attract new members",
               "Provides a streamlined experience for club leaders to manage their clubs and events"),
            "https://pages.opencodingsociety.com/capstone/hawkhub/",
            "https://github.com/SoniDhenuva/HawkHub",
            "https://github.com/SoniDhenuva/hawkhub_spring"
        ));

        list.add(p(
            "SFI Foundation",
            "ML-Powered Spec Search and Role-Based Safety Certification Portal",
            "A working prototype modernizing the SFI Foundation's safety certification system with plain-English search, photo-based gear identification, role-based sign-in, and a no-code staff dashboard.",
            "Today is a prototype review of what an SFI-run system could look like: sign-in with profiles, plain-English search across 140+ safety standards, a photo identifier for safety gear, light quizzes for learning, and a dashboard that lets staff update the site directly. SFI's current site holds the right standards but they are buried — no search, no sign-in, no role-specific views, and every change routes through a developer.",
            "CSP", "Prototype Review",
            ls("Role-based authentication", "Plain-English search (140+ standards)", "Photo-based equipment identification", "Staff content dashboard", "Moderation and audit trail", "Quiz and checkpoint system"),
            ls("Aditya Srivastava", "Dhyan Soni", "Aaryav Lal"),
            ls("Plain-English search across 140+ safety standards",
               "Photo-based equipment identification — on-device, nothing uploaded",
               "Role-based sign-in for drivers, inspectors, and staff",
               "Staff dashboard for updating standards without developer access",
               "Quiz and checkpoint system for standards learning",
               "Moderation built in with full change audit trail"),
            ls("140+ safety standards covered and searchable",
               "3 distinct role types: Drivers, Inspectors, Staff",
               "Same-day content updates from staff without code changes",
               "Prototype ready for production direction from SFI"),
            "https://sfifoundation.opencodingsociety.com",
            null,
            "https://github.com/TheGreppers/greppers"
        ));

        list.add(p(
            "Poway Symphony Orchestra",
            "Redesigned Website Experience",
            "A design-based research capstone that transforms the PSO's digital presence with accessible navigation, concert discovery, interactive games, and responsive design.",
            "An accessible, color-rich site experience that adds faster paths to concerts, tickets, musicians, and joining the orchestra so visitors can discover what matters without digging through dense text. The site adds accessible entry points for patrons, families, and musicians who want to hear the orchestra, find the next concert, explore the roster, or decide whether to join. Instead of feeling like a lesson or a text archive, the experience now feels active, welcoming, and easier to move through.",
            "CSP", "In Development",
            ls("Accessible navigation", "Concert discovery", "Roster exploration", "Responsive design", "EXP progress bar", "Orchestra Builder game", "Virtual Conductor game", "Tune the Orchestra mini-game"),
            ls("Meryl", "Kailyn", "Hope", "Laya"),
            ls("Cleaner home page with direct access to tickets, donations, and season info",
               "Full 2025-2026 season with concert details, program notes, and ticket links",
               "Conductor biography presented in a structured, visual format",
               "Musician directory with section filters and audio previews",
               "Consolidated Join page covering registration, rehearsals, attire, and contact info",
               "Fully responsive across mobile, tablet, and desktop"),
            ls("The redesign transforms a basic Google Sites page into a modern, engaging experience that is easier to navigate, more visually appealing, and gives visitors reasons to stay and explore rather than just look up a concert date."),
            "https://pso.opencodingsociety.com/", null, null
        ));

        list.add(p(
            "Poway Neighborhood Emergency Corps",
            "Enhanced Community Preparedness Platform",
            "Enhanced preparedness tools for the Poway NEC website including live risk information, emergency learning games, an AI chatbot, and account tools for volunteer coordination.",
            "Building upon the essential foundation of powaynec.com, this project enhances a vital community resource into a more dynamic and interactive neighborhood preparedness platform. The additions include a Live Risk Watch feature that estimates wildfire, flood, and extreme heat danger from current conditions, a role-based login system for admins, volunteers, and residents, an AI preparedness chatbot, and an emergency situational mini game for younger learners.",
            "CSP", "Prototype",
            ls("Jekyll Templates", "YAML + Markdown Content", "SCSS Components", "Responsive Layouts", "Machine Learning (Risk Watch)", "AI Chatbot"),
            ls("Aneesh Deevi", "Ethan Patel", "Samarth Vaka"),
            ls("Live Risk Watch estimates wildfire, flood, and extreme heat danger from current conditions",
               "Admin, Volunteer, and Resident login system for coordinated access",
               "AI Preparedness Chatbot for quick answers to emergency questions",
               "Emergency Situational Mini Game for preparedness practice"),
            ls("Residents can review local risk signals from the homepage",
               "Admin and volunteer workflows support clearer coordination",
               "Preparedness questions can be answered quickly via AI chatbot",
               "The game gives younger learners an accessible way to practice preparedness"),
            "https://pnec.opencodingsociety.com", null, null
        ));

        list.add(p(
            "Doing Exceptional Deeds",
            "Community Support",
            "An extension for the Doing Exceptional Deeds non-profit website, uplifting individuals and strengthening communities through education-first programs.",
            "An extension for the Doing Exceptional Deeds non-profit website, uplifting youth and communities through education-first programs. The project adds an AI community assistant, a full website redesign, a public deed submission feature, an interactive photo carousel, and an admin-user chat system to strengthen the organization's digital presence.",
            "CSP", "Active",
            ls("AI Assistant", "Frontend Redesign", "Photo Carousel", "Admin Chat System", "Public Submission Form"),
            ls("William Windle", "Ethan Wong", "Nicolas Diaz"),
            ls("AI Community Assistant for instant visitor support",
               "Redesigned website homepage and navigation",
               "Public Deed Submission for community members to share stories",
               "Photo Carousel showcasing positive community moments",
               "Admin-User Chat System for direct communication"),
            ls("Delivers a clean, engaging homepage that makes programs and actions easy to find",
               "Provides instant support through an AI assistant, reducing wait times for help",
               "Encourages community involvement by allowing users to submit their own exceptional deeds",
               "Showcases positive stories through an interactive photo carousel",
               "Strengthens communication with a direct chat system between users and administrators"),
            "https://www.doingexceptionaldeeds.org/", null, null
        ));

        list.add(p(
            "ACS Cancer Infograph",
            "One Diagram. Every Cancer. Instantly.",
            "An interactive human-body diagram consolidating American Cancer Society cancer information into one visual interface so patients and families find what they need in seconds.",
            "A design-based research capstone that reimagines the American Cancer Society's All About Cancer page. Instead of scrolling through dozens of text links, users interact with an annotated diagram of the human body — each labeled region links directly to the relevant ACS cancer information page. Cancers are positioned anatomically with clear visual callouts, and users can access a plain-English AI assistant, a Cell Cycle Lab, an interactive RPG, and an ML-powered risk calculator.",
            "CSP", "In Production",
            ls("Interactive SVG Body Diagram", "JavaScript Click Routing", "ACS Design System (CSS)", "ARIA Accessibility Layer", "Responsive / Mobile Layout", "GitHub Pages Deployment"),
            ls("Aashika", "Anwita", "Varada"),
            ls("Interactive anatomical body map linking cancer types to symptoms, risk factors, and ACS resources",
               "Plain-English AI assistant answers follow-up questions directly within the body map",
               "Cell Cycle Lab simulates phase checkpoints, gene toggles, and live mutation tracking",
               "Story-driven RPG teaches cell biology and cancer concepts through interactive gameplay",
               "ML-powered risk calculator delivers personalized scores from age, lifestyle, and medical history",
               "Community blog keeps patients, families, and learners connected to ACS news and stories"),
            ls("Reduces cancer information lookup from 5+ clicks to a single fast interaction, especially on mobile",
               "Lowers cognitive load for patients, caregivers, and first-time visitors navigating care during stressful moments",
               "Improves ACS accessibility for users unfamiliar with cancer terminology or medical language",
               "Consolidates 30+ standalone cancer links into one cohesive visual entry point with anatomy-based navigation",
               "Supports earlier detection by making trusted cancer resources faster and easier to discover at first search"),
            "https://acs.opencodingsociety.com", null, null
        ));

        list.add(p(
            "Poway Woman's Club",
            "Website Refurbishment for a 65-Year-Old Nonprofit",
            "Rebuilding the Poway Woman's Club website with member portals, online payments, live messaging, event calendar, and a fresh UI while preserving the heart of the original site.",
            "Three students partnering with the Poway Woman's Club, a 501(c)(3) nonprofit founded in 1960. The project rebuilds the club's website with member portals, an event calendar, live messaging, and an admin dashboard. Six decades of scholarships, art exhibits, and youth programs deserve a site that is as welcoming as the club itself. Features include Google-based login, a groups system, RSVP-enabled event calendar, blog posting, and a no-code admin control center.",
            "CSP", "In Development",
            ls("Responsive Frontend", "User Auth & Profiles", "Event Calendar", "Live Messaging", "Admin Dashboard", "Blog Engine", "Google Authorization"),
            ls("Evan S", "Maya D", "Cyrus Z"),
            ls("Login system with local accounts or one-click Google authorization",
               "Friends and live messaging to connect members between meetings",
               "Groups system to filter calendar and see only relevant events",
               "Event calendar with RSVP directly from dashboard",
               "Blog posting for members to share updates and club news",
               "Admin dashboard for club leaders to manage without touching code"),
            ls("First-time visitors immediately see the club's mission",
               "Members stay connected between meetings",
               "Administrators manage everything without touching code"),
            "https://poway-women-s-club.github.io/pwc/",
            "https://github.com/Poway-Women-s-Club/pwc",
            "https://github.com/Poway-Women-s-Club/pwc-flask"
        ));

        list.add(p(
            "UESL Foundation",
            "Gaming for All — Inclusion Through Esports",
            "Built an AI chatbot, accessible game engine with 8 IDD-friendly modes, and a social platform to extend UESL's reach for individuals with intellectual and developmental disabilities across San Diego.",
            "A 501(c)(3) nonprofit (EIN: 88-2591302) dedicated to supporting individuals with intellectual and developmental disabilities. UESL provides esports competitions, multi-location gaming arenas, and structured career development pathways — giving participants meaningful connection, skill-building, and a path into the STEM and gaming workforce. The capstone work adds a UESL Coach chatbot, an inclusive tournament platform with 8 IDD-specific accessibility modes, and a community social hub with microblog posts, game leaderboards, and real-time co-op multiplayer via WebSocket.",
            "CSP", "Active 501(c)(3) Nonprofit",
            ls("Esports Competitions", "Multi-Location Gaming Arenas", "STEM Career Pathway Programs", "Bilingual Outreach Tools", "Year-Round Scheduling Platform", "AI Coach Chatbot (Groq/LLaMA)", "Inclusive Game Engine (8 IDD modes)", "Socket.IO Multiplayer"),
            ls("Sathwik Kintada", "Rudra B Joshi", "Darshan"),
            ls("Serves San Diego & Imperial Counties year-round",
               "Gaming arenas open independent of school calendars",
               "Career & college pathways in STEM and gaming industries",
               "Builds communication, collaboration & problem-solving skills",
               "Spanish-language programming for broader community access",
               "Hosts Special Needs Super Smash Grand Championship events"),
            ls("Year-round access to competitive gaming and social community",
               "Structured career development into gaming & technology industries",
               "Inclusive championship events celebrating diverse abilities",
               "Bilingual support expanding reach across cultural communities",
               "Multi-site arena presence across two California counties"),
            "https://www.unifiedesl.com/foundation", null, null
        ));

        list.add(p(
            "DeFlock SD",
            "Community Mapping to Resist ALPR Surveillance",
            "A crowdsourced map and tool set to document Automatic License Plate Reader (ALPR) surveillance in San Diego and support community resistance.",
            "DeFlock SD is a regional chapter of deflock.org focused on documenting and exposing the spread of ALPRs in San Diego. The platform allows community members to report camera locations, visualize surveillance infrastructure, and access resources to understand the privacy and civil liberties risks of ALPR systems. The City of San Diego was one of the first adopters of the Flock surveillance system, and this project builds tools for community transparency and informed resistance.",
            "CSP", "Active",
            ls("Flask RESTful API (Backend)", "Jekyll (Frontend)", "Geospatial Data Analysis", "Path Routing Algorithms"),
            ls("Adhav", "Lucas", "Perry"),
            ls("Crowdsourced map of ALPR cameras across San Diego",
               "Tools for community reporting, verification, and transparency around surveillance infrastructure",
               "Resources explaining ALPR technology, risks, and policies",
               "Open source platform for local resistance to mass surveillance"),
            ls("Exposes the bipartisan push to implement mass surveillance systems to track people",
               "Documents the City of San Diego's adoption of Flock surveillance despite community backlash",
               "Highlights collaboration between Flock, the City, and external agencies including ICE",
               "Provides community tools to understand and respond to ALPR surveillance",
               "Reveals documented security vulnerabilities and abuses in the Flock system"),
            "http://deflock.opencodingsociety.com/",
            "https://github.com/the-flockers/frontend",
            "https://github.com/the-flockers/backend"
        ));

        list.add(p(
            "Soroptimist International of Poway",
            null,
            "We analyzed sipoway.com to document the organization's programs and recommend UI improvements that help donors, volunteers, and program applicants take action.",
            "We analyzed sipoway.com to document the organization's programs and recommend UI improvements that help donors, volunteers, and program applicants take action. Soroptimist International of Poway is a service organization dedicated to improving the lives of women and girls through programs focusing on education and economic empowerment.",
            "CSP", null,
            ls(),
            ls("Anishka Sanghvi", "Michelle Ji", "Krishna Visvanath"),
            ls(),
            ls(),
            "https://sip.opencodingsociety.com/",
            "https://github.com/cspcodewarriors/codewarrior-pages",
            "https://github.com/cspcodewarriors/codewarriorflask"
        ));

        list.add(p(
            "Sentri",
            null,
            "A comprehensive recovery ecosystem for the Poway Recovery Center with an intelligent program guide, personalized meeting schedules, and long-term sobriety milestone tracking.",
            "A comprehensive recovery ecosystem for the Poway Recovery Center that utilizes an intelligent guide to match users with specialized support programs, provides personalized meeting schedules, and tracks long-term sobriety milestones through a secure, high-fidelity user profile and dashboard.",
            "CSP", null,
            ls(),
            ls("Lilian Wu", "Anika Marathe", "Jaynee Chauhan"),
            ls(),
            ls(),
            null, null, null
        ));

        list.add(p(
            "Friends of the Poway Library",
            "Rebuilding the Friends of the Poway Library website to make it modern, functional, and community-facing",
            "A prototype redesign of the Friends of the Poway Library website with a live bookstore catalog, events calendar, and interactive games.",
            "A prototype redesign of the Friends of the Poway Library website, built to better serve their community. The goal is to give visitors a reason to return, with a live bookstore catalog they can browse before visiting, an embedded events calendar, interactive games with profiles and leaderboards, a donation flow, and an interactive history timeline.",
            "CSP", "Prototype",
            ls("Jekyll", "JavaScript", "Spring Boot", "REST APIs"),
            ls("Shayan Bhatti", "Arnav Pallapotu", "Tanay Paranjpe"),
            ls("Browse and search the bookstore catalog before you walk in",
               "See upcoming library events without ever leaving the site",
               "Read past newsletters and stay connected to the community",
               "Explore the library's history through an interactive timeline",
               "Six games that make the library fun and worth coming back to"),
            ls("Bookstore catalog online means visitors can plan purchases before they arrive",
               "Events live on the site so the community stays informed in one place",
               "Six games give people a real reason to return to the site",
               "A donation flow will let supporters contribute directly for the first time"),
            null, null, null
        ));

        list.add(p(
            "DSA Website Redesign",
            "One Dashboard. Every Resource. Zero Clicks Wasted.",
            "A comprehensive redesign proposal for the Deputy Sheriffs' Association of San Diego County website — improving member experience through an interactive dashboard, smart FAQ, and enhanced navigation.",
            "Currently, DSA members navigate through 9+ menu items and multiple pages to find benefits, forms, meeting minutes, and event tickets. The Latest News section only shows 2 static cards. This redesign consolidates everything into a single personalized hub — quick-access tiles, filterable news, events calendar, and document center — reducing the average user journey from 4 pages to 1 click. It also adds a Smart FAQ Hub with instant fuzzy search and a mega menu navigation system.",
            "CSP", "Main Feature",
            ls("WordPress REST API", "React Dashboard UI", "Authentication Integration", "Calendar Widget", "Fuzzy Search (Fuse.js)", "Push Notifications", "CSS Animations"),
            ls("Akhil", "Neil", "Moiz"),
            ls("Quick-access tiles for benefits, forms, events, and store",
               "Live filterable news feed with newsletters, minutes, and announcements",
               "Upcoming events calendar with RSVP functionality",
               "Searchable document center with categorized downloads",
               "Personalized notifications for new releases and updates",
               "Mobile-responsive design for on-the-go access"),
            ls("Members find resources in 1 click vs. navigating 3-4 pages",
               "Increased engagement with news, events, and benefits",
               "Reduced support inquiries through self-service design",
               "Modern professional look reflecting DSA's prestige",
               "Mobile-first ensures 50%+ of users on phones have great UX"),
            null, null, null
        ));

        list.add(p(
            "D.A.D. Website Redesign",
            "From Text-Heavy to Visually Compelling in 3 Seconds",
            "A comprehensive redesign proposal for the Doing Exceptional Deeds nonprofit website — elevating digital presence through impact storytelling, a streamlined donation flow, and dedicated program pages.",
            "The current D.A.D. homepage has cramped navigation, small fonts, a wall-of-text mission section, and no visible impact metrics. The redesign leads with a full-width hero, animated counters showing youth served and programs offered, visual program cards, a testimonial carousel, and a prominent donation CTA. Individual program pages with hero banners, photo galleries, and embedded registration forms make discovery and sign-up self-service.",
            "CSP", "Main Feature",
            ls("Wix Custom Components", "CSS Grid / Flexbox", "Animated Counters JS", "Testimonial Carousel", "Responsive Breakpoints", "SEO Optimization", "Wix Payments / PayPal", "Progress Bar Component"),
            ls("Neil", "Moiz", "Akhil"),
            ls("Full-width hero with bold tagline and dual CTAs",
               "Animated impact metrics bar (youth served, programs, years)",
               "Visual mission section with icons instead of text walls",
               "Program spotlight cards with images and descriptions",
               "Testimonial carousel from families and community",
               "Prominent donation CTA with impact statements"),
            ls("First impression becomes visually compelling in under 3 seconds",
               "Impact metrics build immediate credibility with donors",
               "Clear CTAs guide visitors toward donating or getting involved",
               "Program cards make discovering initiatives effortless",
               "Mobile-optimized for 50%+ of visitors on phones"),
            null, null, null
        ));

        list.add(p(
            "RCR: Poway-Midland Railroad",
            "From static text table to live departure board",
            "Modernizing the Poway-Midland Railroad website with a live departure board, online reservation system, ML visitor forecast, interactive fleet encyclopedia, and volunteer scheduling portal.",
            "Transforming the Poway-Midland Railroad from a static informational website into a dynamic, data-driven visitor platform. The original site had a plain HTML table listing dates and times only. The redesign connects to a Flask backend to show real seat counts for every departure, adds the first-ever online reservation system, an ML-powered visitor forecast using Gradient Boosting (R2=0.93), an interactive fleet encyclopedia, a 7-era history timeline, a volunteer scheduling portal, 360-degree panoramic views, and a community notes board.",
            "CSP", "Prototype",
            ls("Flask / Python Backend", "SQLite Database", "REST API", "Jekyll Frontend", "Scikit-learn (Gradient Boosting)", "Open-Meteo Weather API", "Pannellum 360 viewer", "Leaflet.js Interactive Map"),
            ls("Rebecca", "Cyrus", "Rishabh"),
            ls("Real-time departure board with live seat counts per ride",
               "Date switcher — view any past or future operating day",
               "Animated live train position tracker across route stops",
               "Full booking form with unique confirmation code (PMR-XXXXXX)",
               "Seat conflict detection prevents overbooking in real time",
               "ML visitor forecast with weather and school calendar integration",
               "Interactive fleet encyclopedia with 9 vehicles, photos, and restoration timelines",
               "7-era clickable interactive history timeline",
               "Volunteer scheduling portal replacing phone and paper coordination",
               "360-degree panoramic scenes with Leaflet route map",
               "Community notes board with photo sharing and likes"),
            ls("Replaced static date table with a live, queryable departure board",
               "Created PMRR's first-ever online reservation capability",
               "Eliminates risk of visitors arriving to a full train",
               "Entirely new ML visitor forecast feature — nothing like it on original site",
               "Transformed unread text history into an engaging interactive exhibit",
               "First digital volunteer scheduling tool for PMRR",
               "First community and social feature for PMRR"),
            "https://rcr.opencodingsociety.com/", null, null
        ));

        list.add(p(
            "Poway Veterans Organization",
            "A Clear, Compassionate Pathway for Every Veteran in Need",
            "Three purpose-built tools that transform how veterans receive help, how volunteers get started, and how questions get answered for the Poway Veterans Organization.",
            "Three purpose-built tools that transform how veterans receive help, how volunteers get started, and how questions get answered — turning powayveterans.org into a clear, compassionate local support portal. The Get Help Hub provides a quick eligibility check across six assistance areas with a dynamic document checklist. The Volunteer Onboarding redesign leads with the opportunity and completes sign-up in under two minutes. The PVO Chatbot provides 24/7 AI-powered answers trained on PVO's full knowledge base.",
            "CSP", null,
            ls("Machine Learning (eligibility logic)", "Google AI Search", "Dynamic Checklists", "AI Chatbot", "Volunteer Onboarding System"),
            ls("Alice", "Brandon", "Aryan"),
            ls("Quick eligibility check across six assistance areas (rent, utilities, food, transportation, home repair, emergency hardship)",
               "Transparent response time expectations and document checklist provided upfront",
               "Simple 3-step process: check eligibility, gather documents, submit",
               "Urgent and local resources highlighted for immediate needs",
               "Privacy-first, trust-focused approach throughout",
               "Volunteer onboarding leads with role opportunities before the sign-up form",
               "Sub-2-minute volunteer sign-up form engineered to capture intent at peak motivation",
               "AI chatbot trained on PVO's programs, eligibility criteria, and local partner network"),
            ls("Simplifies the veteran assistance application process",
               "Reduces confusion and incomplete applications while helping veterans apply with confidence",
               "Provides instant 24/7 AI-powered answers without hold times or callbacks",
               "Eliminates phone call barriers with online volunteer onboarding",
               "Extends PVO's reach beyond business hours at no additional staffing cost"),
            "https://pvo.opencodingsociety.com/MelTitanic", null, null
        ));

        list.add(p(
            "SD Auto",
            "AI-Driven Intelligent Routing for Smarter Commutes",
            "A full-stack intelligent routing platform that enhances daily commutes in San Diego through real-time traffic data, community hazard reporting, and AI-driven route optimization.",
            "SD Auto is a full-stack intelligent routing platform designed to enhance daily commutes in San Diego, California. By combining real-time traffic data, community hazard reporting, and machine learning-driven route optimization, SD Auto helps commuters navigate congestion using smarter tools. The platform features an ML prediction engine built with Scikit-learn that analyzes historical traffic trends, time-of-day patterns, and crowd-sourced hazard data to deliver optimal routes.",
            "CSP", "Active Full-Stack Development",
            ls("Flask & Python Backend", "TailwindCSS & JavaScript Frontend", "Leaflet Interactive Maps", "Scikit-learn ML Models", "SQLAlchemy & REST APIs", "Pandas Data Processing"),
            ls("Ahaan", "Arnav"),
            ls("ML-Powered Smart Route Finder (Scikit-learn)",
               "Real-Time Hazard Reporting & Map Visualization (Leaflet)",
               "Daily Routine Planner with Automated Scheduling",
               "Favorite Locations with Quick-Access Saved Routes",
               "Location-Verified User Authentication System",
               "Community-Driven Crowd-Sourced Traffic Data"),
            ls("Reduces Commute Times with AI-Optimized Routes",
               "Improves Road Safety via Community Hazard Alerts",
               "Automates Daily Trip Planning & Scheduling",
               "Provides Real-Time Traffic Visualization",
               "Enables Crowd-Sourced Hazard Intelligence",
               "Location-Verified Secure User Experience"),
            "https://pages.opencodingsociety.com/capstone/sd-auto/",
            "https://github.com/Ahaanv19/SD_Auto_Frontend",
            "https://github.com/Ahaanv19/SD_Auto_Backend"
        ));

        list.add(p(
            "Friends of Poway Seniors",
            "Making Senior Support Accessible Online",
            "A design-based research capstone that rebuilds the Friends of Poway Seniors web presence with clarity and compassion, adding a meal tracker, AI chatbot, and gamified bingo.",
            "A design-based research capstone that rebuilds the Friends of Poway Seniors web presence with clarity and compassion. The organization provides essential services to Poway's elderly community — meal deliveries, social connections, transportation assistance, and wellness checks — but its current site buries this mission under cluttered navigation and unstructured layouts. This project delivers a clean, intuitive interface with simplified menus, prominent CTAs, a meal tracker, ML forecasting for events, a gamified bingo experience, and an AI chatbot for 24/7 support.",
            "CSP", "In Planning",
            ls("Simplified Navigation Architecture", "Mobile-First Responsive Design", "Meal Tracker Dashboard", "Prominent CTA Integration", "ML Event Forecasting", "AI Chatbot (24/7)", "Interactive Bingo Game", "Real Photography Integration"),
            ls("Vivian Zhang", "Nitya R", "Virginia Z"),
            ls("Simplify navigation — reduce dropdown clutter from 7+ options to 3 clear links",
               "Add prominent CTA buttons (Donate, Volunteer, Get Help) above the fold",
               "Clean up layout with consistent spacing and logical visual hierarchy",
               "Introduce meal tracker for seniors to log daily nutrition",
               "Mobile-responsive design for elderly users and caregivers",
               "Surface contact info, hours, and location in a prominent banner",
               "AI chatbot for 24/7 support in plain English",
               "Volunteer signup with schedule management"),
            ls("Reduces cognitive load for elderly users through simplified menus",
               "Makes donation and volunteer opportunities impossible to miss",
               "Builds trust through authentic photography of real community impact",
               "Helps seniors and caregivers track daily nutrition with meal logger",
               "Ensures critical contact info is always visible and accessible",
               "Preserves the organization's mission while modernizing its presentation",
               "Sets a foundation for future digital services (event RSVP, meal scheduling)"),
            "https://fops.opencodingsociety.com/", null, null
        ));

        list.add(p(
            "Safe Passage Heals",
            "Media Management Tools and Interactive Recovery Simulation",
            "A two-part web platform for Safe Passage Heals with a dynamic event calendar and an interactive Path to Recovery simulation for domestic violence education.",
            "A two-part web platform built for Safe Passage Heals. The Dynamic Event Calendar centralizes all upcoming workshops, support groups, and community events in an interactive monthly view — with a secure admin dashboard for staff to add, edit, and delete events in real time. Path to Recovery is an interactive neighborhood simulation where users learn the domestic violence response process step by step, meet characters like counselors and hotline advocates, take personalized diagnostic quizzes, and consult a confidential AI assistant.",
            "CSP", "Working Prototype",
            ls("Dynamic Event Calendar", "Admin Dashboard", "Interactive Recovery Simulation", "AI Chatbot", "REST API", "Flask Backend"),
            ls("Ruchika Kench", "Akshara Shankar", "Avantika Chittari"),
            ls("Interactive event calendar shows all upcoming Safe Passage Heals events with important event details",
               "Login system allows admins to access an event management dashboard",
               "Admin dashboard allows for simple and centralized event management — add, edit, and delete events",
               "Admins can add new events through an interactive form that automatically updates the calendar and blog",
               "Path to Recovery is an interactive simulation where users travel through a virtual neighborhood and learn about the domestic violence recovery process",
               "User interacts with various characters (trusted adults, therapist, counselor, domestic violence hotline) step by step",
               "Short diagnostic quizzes and a confidential AI assistant provide personalized guidance"),
            ls("Eliminates scattered event information by centralizing all Safe Passage Heals events into one calendar",
               "A secure admin dashboard makes event management effortless from one centralized place",
               "Turns passive awareness into active learning through an interactive simulation",
               "Makes education on domestic violence response accessible and engaging through a confidential AI assistant, diagnostic quizzes, and character-driven storytelling"),
            "https://ruchikak19.github.io/flaskandfurious", null, null
        ));

        return list;
    }

    private CapstoneProject p(
            String title, String subtitle, String description, String about,
            String courseCode, String status,
            List<String> tech, List<String> teamMembers,
            List<String> keyPoints, List<String> impact,
            String pageUrl, String frontendUrl, String backendUrl) {
        return p(title, subtitle, description, about, courseCode, status, tech, teamMembers, keyPoints, impact, pageUrl, frontendUrl, backendUrl, null);
    }

    private CapstoneProject p(
            String title, String subtitle, String description, String about,
            String courseCode, String status,
            List<String> tech, List<String> teamMembers,
            List<String> keyPoints, List<String> impact,
            String pageUrl, String frontendUrl, String backendUrl, String imageUrl) {
        CapstoneProject cp = new CapstoneProject();
        cp.setTitle(title);
        cp.setSubtitle(subtitle);
        cp.setDescription(description);
        cp.setAbout(about);
        cp.setCourseCode(courseCode);
        cp.setStatus(status);
        cp.setTech(new ArrayList<>(tech));
        cp.setTeamMembers(new ArrayList<>(teamMembers));
        cp.setKeyPoints(new ArrayList<>(keyPoints));
        cp.setImpact(new ArrayList<>(impact));
        cp.setPageUrl(pageUrl);
        cp.setFrontendUrl(frontendUrl);
        cp.setBackendUrl(backendUrl);
        cp.setImageUrl(imageUrl);
        return cp;
    }

    private List<String> ls(String... values) {
        return new ArrayList<>(java.util.Arrays.asList(values));
    }

    private boolean isMySql(Connection conn) throws SQLException {
        String url = conn.getMetaData().getURL();
        return url != null && url.startsWith("jdbc:mysql:");
    }

    private String q(Connection conn, String name) throws SQLException {
        String quote = conn.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) return name;
        return quote + name.replace(quote, quote + quote) + quote;
    }

    private String buildCapstoneProjectsTableSql(Connection conn) throws SQLException {
        String idCol = isMySql(conn)
                ? q(conn, "id") + " BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
                : q(conn, "id") + " INTEGER PRIMARY KEY AUTOINCREMENT";
        return "CREATE TABLE IF NOT EXISTS " + q(conn, "capstone_projects") + " ("
                + idCol + ","
                + q(conn, "title") + " VARCHAR(255) NOT NULL UNIQUE,"
                + q(conn, "subtitle") + " VARCHAR(255),"
                + q(conn, "description") + " TEXT,"
                + q(conn, "about") + " TEXT,"
                + q(conn, "course_code") + " VARCHAR(255),"
                + q(conn, "status") + " VARCHAR(255),"
                + q(conn, "page_url") + " VARCHAR(255),"
                + q(conn, "frontend_url") + " VARCHAR(255),"
                + q(conn, "backend_url") + " VARCHAR(255),"
                + q(conn, "image_url") + " TEXT,"
                + q(conn, "created_at") + " TIMESTAMP"
                + ");";
    }

    private String buildCollectionTableSql(Connection conn, String table, String valueCol) throws SQLException {
        String fkType = isMySql(conn) ? "BIGINT" : "INTEGER";
        return "CREATE TABLE IF NOT EXISTS " + q(conn, table) + " ("
                + q(conn, "capstone_project_id") + " " + fkType + ","
                + q(conn, valueCol) + " VARCHAR(255)"
                + ");";
    }
}
