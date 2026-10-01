package com.tradevision.model;

/**
 * User's own explicit design: "for the first production version, I'd keep it simpler:
 * End-of-session: [ CLOSE ALL PLAN POSITIONS ] or [ KEEP POSITIONS OPEN ]. Avoid complicated
 * rules until they've been tested." Deliberately does NOT include "close only profitable" /
 * "close only losing" -- the user's own explicit deferral, not an oversight.
 */
public enum EndOfSessionAction {
    CLOSE_POSITIONS,
    KEEP_OPEN
}
