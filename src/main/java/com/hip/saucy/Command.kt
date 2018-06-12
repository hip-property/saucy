/*-
 * =========================================================BeginLicense
 * Saucy
 * .
 * Copyright (C) 2018 HiP Property
 * .
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ===========================================================EndLicense
 */
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
