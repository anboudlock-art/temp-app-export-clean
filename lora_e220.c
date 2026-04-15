/*
 * lora_e220.c - E220-M900T22S LoRa Driver (replaces Uart_4G.c + e103w08b.c)
 * UART1: P0.05=RXD1, P0.06=TXD1, 9600 baud
 */
#include "lora_e220.h"
#include "config.h"
#include "uart.h"
#include "gpio.h"
#include "DebugLog.h"
#include "delay.h"
#include "user.h"
#include "typedef.h"

static UART_CTRL_TYPE *UART_1_CTRL = ((UART_CTRL_TYPE *)UART_1_CTRL_BASE);

/* Global variables (replaces net4G structure) */
lora_ctrl_t lora_ctrl;
uint8_t report_cnt = 0;  /* was in Uart_4G.c, needed by input.c */

/* UART RX buffer */
static uint8_t lora_rx_data[64];
static uint16_t lora_rx_cnt = 0;

/* ---- UART1 Functions ---- */
static void lora_uart_init(void)
{
    PIN_CONFIG->PIN_5_SEL = PIN_SEL_UART_RXD1;
    PIN_CONFIG->PIN_6_SEL = PIN_SEL_UART_TXD1;
    PIN_CONFIG->PAD_5_INPUT_PULL_UP = 0;
    PIN_CONFIG->PAD_6_INPUT_PULL_UP = 0;

    UART_1_CTRL->CLK_SEL = 0;
    UART_1_CTRL->BAUDSEL = UART_BAUD_9600;  /* E220 default */
    UART_1_CTRL->FLOW_EN = UART_RTS_CTS_DISABLE;
    UART_1_CTRL->RX_INT_MASK = 0;
    UART_1_CTRL->TX_INT_MASK = 1;
    UART_1_CTRL->PAR_FAIL_INT_MASK = 1;
    UART_1_CTRL->par_rx_en = 0;
    UART_1_CTRL->par_tx_en = 0;
    UART_1_CTRL->RI = 0;
    UART_1_CTRL->TI = 0;
    UART_1_CTRL->PAR_FAIL = 1;
    UART_1_CTRL->RX_FLUSH = 1;

    lora_rx_cnt = 0;
    NVIC_EnableIRQ(UART1_IRQn);
    UART_1_CTRL->UART_EN = 1;
}

void lora_uart_write(uint8_t data)
{
    UART_1_CTRL->TX_DATA = data;
    while(UART_1_CTRL->TI == 0);
    UART_1_CTRL->TI = 0;
}

void lora_uart_write_buff(uint8_t *pData, uint8_t len)
{
    uint16_t i;
    for(i = 0; i < len; i++) lora_uart_write(pData[i]);
}

static void lora_uart_read(uint8_t *pcnt, uint8_t *pbuf)
{
    uint8_t i = 0;
    volatile uint8_t dly = 0;
    while(!UART_1_CTRL->RX_FIFO_EMPTY) {
        *(pbuf+i) = UART_1_CTRL->RX_DATA;
        i++;
        dly++; dly++; dly++;
    }
    *pcnt = i;
}

/* ---- E220 Control ---- */
void lora_e220_init(void)
{
    flg_4g_EN = 1;
    flg_needred3 = 1;
    _user.powerondelay1s = 200/2;
    lora_uart_init();
    lora_ctrl.state = LORA_STATE_READY;
    dbg_printf("LoRa_init\r\n");
}

void lora_e220_deinit(void)
{
    UART_1_CTRL->UART_EN = 0;
    NVIC_DisableIRQ(UART1_IRQn);
    GPIO_Pin_Clear(U32BIT(PWR_4G));  /* reuse same power pin */
    lora_ctrl.state = LORA_STATE_IDLE;
    flg_4g_EN = 0;
}

void lora_send_status(uint8_t status)
{
    uint8_t frame[7];
    frame[0] = 0x00; frame[1] = 0x00;  /* addr */
    frame[2] = 0x04;                     /* channel */
    frame[3] = _user.lock_sn8array.data8[2];
    frame[4] = _user.lock_sn8array.data8[3];
    frame[5] = 0x12;                     /* type */
    frame[6] = status;
    lora_uart_write_buff(frame, 7);
}

void lora_process(void)
{
    if(lora_ctrl.state == LORA_STATE_READY) {
        if(lora_ctrl.senddelaytime > 0) lora_ctrl.senddelaytime--;
    }
}

/* ---- Compatibility wrappers (called by input.c etc.) ---- */
void e103w08b_init(void)
{
    lora_e220_init();
}

void e103w08b_uart_write_buff(uint8_t *pData, uint8_t len)
{
    lora_uart_write_buff(pData, len);
}

void e103w08b_uart_write(uint8_t data)
{
    lora_uart_write(data);
}

/* ---- UART1 IRQ Handler ---- */
void UART1_IRQHandler(void)
{
    if((UART_1_CTRL->RI) == 1) {
        uint8_t i, len, buf[4];
        UART_1_CTRL->RI = 0;
        lora_uart_read(&len, buf);
        for(i = 0; i < len; i++) {
            if(lora_rx_cnt < 64) lora_rx_data[lora_rx_cnt++] = buf[i];
        }
    }
}
