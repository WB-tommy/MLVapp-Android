#ifndef _MLVCORE_H_
#define _MLVCORE_H_

#ifdef __cplusplus
extern "C" {
#endif

/* Version information */
const char* mlvcore_version(void);

/* Library initialization */
int mlvcore_init(void);

/* Library cleanup */
void mlvcore_cleanup(void);

#ifdef __cplusplus
}
#endif

#endif /* _MLVCORE_H_ */