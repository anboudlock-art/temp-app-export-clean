#ifndef LORA_E220_H_
#define LORA_E220_H_

#include "stdint.h"

#define LORA_STATE_IDLE     0
#define LORA_STATE_READY    2

typedef struct {
    uint8_t state;
    uint8_t senddelaytime;
    uint8_t senddelaytime_set;
    uint8_t lost_cnt;
} lora_ctrl_t;

extern lora_ctrl_t lora_ctrl;
extern uint8_t report_cnt;

/* LoRa API */
extern void lora_e220_init(void);
extern void lora_e220_deinit(void);
extern void lora_process(void);
extern void lora_send_status(uint8_t status);
extern void lora_uart_write(uint8_t data);
extern void lora_uart_write_buff(uint8_t *pData, uint8_t len);

/* Compatibility wrappers (so input.c etc. don't need changes) */
extern void e103w08b_init(void);
extern void e103w08b_uart_write_buff(uint8_t *pData, uint8_t len);
extern void e103w08b_uart_write(uint8_t data);

#endif
