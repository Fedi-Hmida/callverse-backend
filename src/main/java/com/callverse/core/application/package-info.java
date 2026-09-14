/**
 * Use cases, one class per thing the system can be asked to do, organised as CQRS feature
 * slices. Handlers are plain Java with constructor injection; infrastructure.config is what
 * turns them into Spring beans.
 */
package com.callverse.core.application;
