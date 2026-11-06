<?php
// The app is a single-page app; this file just serves the shell.
// Using .php so it works as the default document on cPanel/Apache.
?><!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="theme-color" content="#2b4fb0">
    <title>Chore Slayer</title>
    <link rel="stylesheet" href="assets/styles.css?v=9">
    <link rel="icon" type="image/svg+xml" href="assets/icon.svg?v=2">
    <link rel="icon" type="image/png" sizes="32x32" href="assets/favicon-32.png?v=2">
    <link rel="apple-touch-icon" href="assets/apple-touch-icon.png?v=2">
</head>
<body>
    <a class="skip-link" href="#main">Skip to main content</a>

    <header class="app-header" role="banner">
        <div class="app-header__inner">
            <img class="app-logo" src="assets/icon.svg?v=2" alt="" width="34" height="34">
            <h1 class="app-title">Chore Slayer</h1>
            <div class="app-header__actions" id="header-actions"><!-- filled by JS --></div>
        </div>
    </header>

    <main id="main" tabindex="-1">
        <!-- Live region for screen-reader announcements -->
        <div id="sr-status" class="sr-only" role="status" aria-live="polite"></div>

        <!-- Toast / message banner -->
        <div id="toast" class="toast" hidden></div>

        <!-- Views are swapped in here by app.js -->
        <div id="view"></div>
    </main>

    <footer class="app-footer">
        <small>Made with care for tracking great days. <span id="footer-day"></span> &middot; <a href="privacy.html">Privacy Policy</a></small>
    </footer>

    <script src="assets/app.js?v=11"></script>
</body>
</html>
