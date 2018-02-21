package com.hip.saucy

/**
 * A Command is used in systems that are following
 * CQRS, but not event sourcing.
 *
 * These are traditionally CRUD-style applications.
 *
 * To be good citizens, CRUD services should still emit events
 * describing what has happened to allow downstream services to consume them.
 *
 */
interface Command {

}
