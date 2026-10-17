// Round 25 - the mobile order-taking app (waiter app).
//
// It is a THIN client.  It holds no database and never keeps a bill of its
// own: every screen is a picture of the main server's data and every save is
// a call to that server.  The main PC stays the main PC - if it is off, the
// phone says so and refuses instead of inventing orders.

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
