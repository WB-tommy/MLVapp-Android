/* MLV Core Library - Main wrapper file
 * This file serves as the main entry point for the shared library
 */

#include "mlv_include.h"

/* Version information */
const char* mlvcore_version(void) {
    return "1.0.0";
}

/* Library initialization - can be expanded later */
int mlvcore_init(void) {
    return 0; /* Success */
}

/* Library cleanup - can be expanded later */  
void mlvcore_cleanup(void) {
    /* Cleanup code if needed */
}