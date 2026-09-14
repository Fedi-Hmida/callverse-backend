/**
 * The inside of the architecture. Everything here is true about CallVerse regardless of how
 * it is delivered or stored, so nothing in this package tree may reference the web layer, the
 * security filter chain or any infrastructure adapter. The dependency rule that guarantees it
 * is asserted by LayerDependencyTest.
 */
package com.callverse.core;
