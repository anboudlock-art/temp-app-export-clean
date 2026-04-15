#define  _HID_C

#include "config.h"
#include "timer.h"
#include "gpio.h"
//#include "rtc.h"
#include "DebugLog.h"
#include "delay.h"

#include "spi.h"
#include "ota.h"
#include "ABD_ble_lock_service.h"
#include <string.h>
#include "uart_protocol.h"
#include "softtimer.h"
#include "wdt.h"

#include "user_app.h"
#include "os_timer.h"
#include "led.h"
#include "battery.h"

#include "my_aes.h"
#include "e103w08b.h"
// // #include "Uart_4G.h" // replaced by lora_e220 // LoRa: 4G removed
#include "user.h"
#include "cpwm.h"
//用户添加
const u8 sn_tab[] = {2,0,1,3,6,0,7,2};//测试用
const u8 keycode_tab[] = {0,3,3,7,1,2};

//XIAO YUE
//const u8 ip_tab[] = {47,115,56,170};
//#define port_value  8601//端口

const u8 ip_tab[] = {159,75,109,42};//hua jing159.75.109.42
#define port_value  8088//端口

//const u8 ip_tab[] = {47,101,190,240};//tt
//#define port_value  2018//端口47.101.190.240

#define eep_ver 	04//03//

#define DATA_SIZE		24//12*2

uint8_t data_write[DATA_SIZE];
uint8_t data_read[DATA_SIZE];

user_object_t _user;
byte_bit_t flag_00;
byte_bit_t flag_01;
GPS_object_t GPS;
eep_save_t eep_save;


//connection

//static uint8_t latency_state = 0;
//uint8_t update_latency_mode=0;

uint8_t update_latency=0;

uint8_t update_latency_mode =0;

uint8_t latency_state =0;

uint8_t ble_status=0;

uint8_t ota_event_flag=0;

uint8_t gatt_Recive_flag=0;

//static uint16_t uart_delay_cnt=0;

static struct gap_att_report_handle *g_report;   //GATT属性列表头,传入到协议栈中进行初始化

const uint8_t HexTab[16]={'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};

uint8_t ADV_DATA[] = {	
						0x02, // length
					  0x01, // AD Type: Flags
					  0x05, // LE Limited Discoverable Mode & BR/EDR Not Supported
	
						0x09,		
						0xFF,		//Type: Manufacturer Specific Data
						0x64,		//电量
						0x00,		//锁状态
						0x64,		//mac地址
						0x69,
						0x4E,
						0x86,
						0x1A,
						0xE8,
						
						0x03,   	// length of this data
						0x02,     // some of the UUID's, but not all
						0xF0, 		//16 Bit UUID
						0xFF, 		//16 Bit UUID  	
	
					  	};

uint8_t SCAN_DATA[]={
						// complete name 设备名字
						0x12,   // length of this data
						0x09,		// GAP_ADVTYPE_LOCAL_NAME_COMPLETE
						'L','O','C','K','_','0','0','0','0','0','0','0','0','0','0','0','0',

						// Tx power level 发射功率
						0x02,   // length of this data
						0x0A,		// GAP_ADVTYPE_POWER_LEVEL
						0,	    // 0dBm
						};

//Uart_Rx_Buf_t uart_rx_buf;		//存放从串口收到的数据
//Uart_Tx_Buf_t uart_tx_buf;		//存放从串口发出的数据


//=================260101+====================//
u32 netUpLoadSendCnt=0;

void TimerNSEvent(void)
{
  u8 i;
  //extern uint8_t ota_state;	
	
	if ( ota_state ){ return;}
	
 
  
  netUpLoadSendCnt+=6;
	if(netUpLoadSendCnt>86400)
	{
		WakeupSource |= RTC_4Greport;
		netUpLoadSendCnt=0;
	}

}						
//================================================//
						
void gpio_init(void)
{
	uint8_t i;
	uint32_t io_input=0,io_output=0,io_outlow=0,io_pull=0, io_invert=0;
	
  #ifdef USER_32K_CLOCK_RCOSC 
	PIN_CONFIG->PAD_INPUT_PULL_UP[0] = 0xff; //pull up 
	#else
  PIN_CONFIG->PAD_INPUT_PULL_UP[0] = 0xfc; //pull up  gpio 0 1 32K xosc
  #endif
    
  PIN_CONFIG->PAD_INPUT_PULL_UP[1] = 0xff; //pull up 
	PIN_CONFIG->PAD_INPUT_PULL_UP[2] = 0xff; //pull up   pin22/pin21 UART
	PIN_CONFIG->PAD_INPUT_PULL_UP[3] = 0xc7; //pull up   //pull up  1100 0111 PAD29/PAD28 SWD  pin21 nrst PAD27  c7 
	PIN_CONFIG->PAD_INPUT_PULL_UP[4] = 0xff; //pull up 
    

	for(i=0;i < 32;i++)
	{
		switch(i)
		{
			#ifdef _GPIO_LED_CONTROL_
			case GPIO_LED_CONNECT:   
			case GPIO_LED_NOTIFYEN:
			case GPIO_LED_WRITEING:
			case GPIO_LED_READING:
				io_output |=U32BIT(i);   //输出低电平
				io_outlow |=U32BIT(i);
			break;
			#endif
			
			#ifndef USER_32K_CLOCK_RCOSC 
			case GPIO_0:
			case GPIO_1:
			break;
			#endif
			
			//用户添加
			case DISCHRG_PIN:
			case WDG_OUT://260101+
				io_output |= U32BIT(i);   //设置成输出
				io_outlow |= U32BIT(i);	 //输出低电平		
			break;
			
			case STATE_KEY:
			case STOP_KEY2:		
			case STOP_KEY1:
			case GPIO_USER_KEY:
				io_input |= U32BIT(i);   //设置成输入	
				io_pull |= U32BIT(i);		//使能内部上拉
			break;
			case lock_hall:
				io_input |= U32BIT(i);   //设置成输入	
			break;
			
			case LED_BLUE:
			case LED_RED:	
			case LED_green:		
			case Motor_INB:
			case Motor_INA:
				io_output |= U32BIT(i);   //输出低电平
				io_outlow |= U32BIT(i);	 //输出低电平
			break;					

			case PWR_4G:	
				io_output |= U32BIT(i);   //输出
				io_outlow |= U32BIT(i);	 //输出低电平
			break;

			case SPCIO:
				io_output |= U32BIT(i);   //OUT
  	  break;		

			case GPIO_21:
			break;
			
			default:   //默认上拉输入
			io_input |= U32BIT(i);
			io_pull |= U32BIT(i);
			break;
		}
 	}
  //YCY 260101
	BBRFWrite(0x7f, 0x00);    //设置GPIO21为IO口模式，而不是复位管脚
	BBRFWrite(0x1a, 0x40);
	
	PIN_Set_GPIO(io_output, PIN_SEL_GPIO);	//功能GPIO
	//取消内部上拉
	PIN_Pullup_Disable(T_QFN_48, io_output|io_input);	
	//DEFAULT OUT
	GPIO_Set_Output(io_output); 
	
	//DEFAULT OUT HIGHT PIN
	GPIO_Pin_Set(io_output);
		//DEFAULT OUT LOW PIN
	GPIO_Pin_Clear(io_outlow);
	
	//INPORT PIN
	GPIO_Set_Input(io_input,io_invert);
	PIN_Pullup_Enable(T_QFN_48, io_pull);

	// GPIO_Input_Enable(U32BIT(lock_hall));//add
	SPCIO_H();
}

void setup_adv_data(void)
{
	struct gap_adv_params adv_params;	
	struct gap_ble_addr dev_addr;
	
	adv_params.type = ADV_IND;
	adv_params.channel = 0x07;  // advertising channel : 37 & 38 & 39
	adv_params.interval = 180; // advertising interval : 1000ms (1600 * 0.625ms)
	adv_params.timeout = 0x64;  // timeout : 100s
	adv_params.policy = 0;			//GAP_ADV_ALLOW_SCAN_ANY_CON_ANY

	SetAdvParams(&adv_params);	
	
	/* bluetooth address */
//	dev_addr.type = PUBLIC_ADDRESS_TYPE;
//	dev_addr.addr[0] = 0x01;
//	dev_addr.addr[1] = 0x02;
//	dev_addr.addr[2] = 0x03;
//	dev_addr.addr[3] = 0x04;
//	dev_addr.addr[4] = 0x05;
//	dev_addr.addr[5] = 0xAA;
//	SetDevAddr(&dev_addr);
	
//	dev_addr.type = PUBLIC_ADDRESS_TYPE;	//已授权的mac地址
//	dev_addr.addr[5]=0x64;
//	dev_addr.addr[4]=0x69;
//	dev_addr.addr[3]=0x4E;
//	dev_addr.addr[2]=0x86;
//	dev_addr.addr[1]=0x1A;
//	dev_addr.addr[0]=0xE8;
//	SetDevAddr(&dev_addr);
	
		/*get bluetooth address */
	GetDevAddr(&dev_addr);
	ADV_DATA[5] = GetBatCapacity();		//获取电量
	ADV_DATA[7] = dev_addr.addr[5];
	ADV_DATA[8] = dev_addr.addr[4];
	ADV_DATA[9] = dev_addr.addr[3];
	ADV_DATA[10] = dev_addr.addr[2];
	ADV_DATA[11] = dev_addr.addr[1];
	ADV_DATA[12] = dev_addr.addr[0];
	#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
	DBGHEXDUMP("addr:\r\n",dev_addr.addr,6);
	#endif
	
	SCAN_DATA[7] = HexTab[dev_addr.addr[5]/16];
	SCAN_DATA[8] = HexTab[dev_addr.addr[5]%16];
	SCAN_DATA[9] = HexTab[dev_addr.addr[4]/16];
	SCAN_DATA[10] = HexTab[dev_addr.addr[4]%16];
	SCAN_DATA[11] = HexTab[dev_addr.addr[3]/16];
	SCAN_DATA[12] = HexTab[dev_addr.addr[3]%16];
	SCAN_DATA[13] = HexTab[dev_addr.addr[2]/16];
	SCAN_DATA[14] = HexTab[dev_addr.addr[2]%16];
	SCAN_DATA[15] = HexTab[dev_addr.addr[1]/16];
	SCAN_DATA[16] = HexTab[dev_addr.addr[1]%16];
	SCAN_DATA[17] = HexTab[dev_addr.addr[0]/16];
	SCAN_DATA[18] = HexTab[dev_addr.addr[0]%16];
	
	SetAdvData(ADV_DATA, sizeof(ADV_DATA), SCAN_DATA, sizeof(SCAN_DATA));

	*(uint8_t *)0x40020014 = 0x01;		//设置ADV_PITV为1，降低功耗
}

/*
uint8_t target
0:ota
1:low energy
*/
void BLSetConnectionUpdate(uint8_t target){
	struct gap_smart_update_params smart_params;
	
	switch(target){
		case 0:   //0:ota
			/* connection parameters */
			smart_params.updateitv_target=0x0010;  //target connection interval (16 * 1.25ms = 20 ms)
			smart_params.updatesvto=0x00c8;  //supervisory timeout (200 * 10 ms = 2s)
			smart_params.updatelatency=0x0000;
			break;
		case 1:   //1:low energy
			/* connection parameters */
			smart_params.updateitv_target=0x0050;  //target connection interval (80 * 1.25ms = 100 ms)
			smart_params.updatesvto=0x0258;  //supervisory timeout (100 * 10 ms = 6s)
			smart_params.updatelatency=0x000A;
			break;
	}	
	if(target<2)
	{
		smart_params.updatectrl=SMART_CONTROL_LATENCY | SMART_CONTROL_UPDATE;
		smart_params.updateadj_num=MAX_UPDATE_ADJ_NUM;
		gap_s_smart_update_latency(&smart_params);
		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
			dbg_printf("smart_params interval:%x latency:%x svto:%x\r\n",smart_params.updateitv_target,smart_params.updatelatency,smart_params.updatesvto);
		#endif
	}
}

void Connection_latency(void){
	update_latency_mode++;
	if(update_latency_mode>=1){
		latency_state=0;
		
		BLSetConnectionUpdate(2);		//0、1有效，2代表不用
		
		Timer_Evt_Stop(EVT_2S);
	}
} 

static void ble_gatt_read(struct gap_att_read_evt evt)
{
	//Device Name
	if(evt.uuid == BLE_DEVICE_NAME_UUID)
	{
		struct gap_ble_addr addr;
		uint8_t DeviceName[17];
		
		GetDevAddr(&addr);
		
		DeviceName[0] = 'L';
		DeviceName[1] = 'o';
		DeviceName[2] = 'c';
		DeviceName[3] = 'k';
		DeviceName[4] = '_';
		
		DeviceName[5] = HexTab[addr.addr[5]/16];
		DeviceName[6] = HexTab[addr.addr[5]%16];
		DeviceName[7] = HexTab[addr.addr[4]/16];
		DeviceName[8] = HexTab[addr.addr[4]%16];
		DeviceName[9] = HexTab[addr.addr[3]/16];
		DeviceName[10] = HexTab[addr.addr[3]%16];
		DeviceName[11] = HexTab[addr.addr[2]/16];
		DeviceName[12] = HexTab[addr.addr[2]%16];
		DeviceName[13] = HexTab[addr.addr[1]/16];
		DeviceName[14] = HexTab[addr.addr[1]%16];
		DeviceName[15] = HexTab[addr.addr[0]/16];
		DeviceName[16] = HexTab[addr.addr[0]%16];

		SetGATTReadRsp(17, (uint8_t *)DeviceName);
	}
	//Manufatcurer Name
	else if(evt.uuid == BLE_MANUFACTURER_NAME_STRING_UUID)
	{

		SetGATTReadRsp(MANUFACTURER_NAME_LEN, (uint8_t *)MANUFACTURER_NAME_REQ);
	}
	//Model Number
	else if(evt.uuid == BLE_MODEL_NUMBER_STRING_UUID)
	{

		SetGATTReadRsp(MODEL_NUMBER_LEN, (uint8_t *)MODEL_NUMBER_REQ);
	}
	//Serial Numner
	else if(evt.uuid == BLE_SERIAL_NUMBER_STRING_UUID)
	{

		SetGATTReadRsp(SERIAL_NUMBER_LEN, (uint8_t *)SERIAL_NUMBER_REQ);
	}
	//Hardware Revision
	else if(evt.uuid == BLE_HARDWARE_REVISION_STRING_UUID)
	{

		SetGATTReadRsp(HARDWARE_REVISION_LEN, (uint8_t *)HARDWARE_REVISION_REQ);
	}
	//Firmware Revision
	else if(evt.uuid == BLE_FIRMWARE_REVISION_STRING_UUID)
	{

		SetGATTReadRsp(FIRMWARE_REVISION_LEN, (uint8_t *)FIRMWARE_REVISION_REQ);
	}
	//Software Revision
	else if(evt.uuid == BLE_SOFTWARE_REVISION_STRING_UUID)
	{

		SetGATTReadRsp(SOFTWARE_REVISION_LEN, (uint8_t *)SOFTWARE_REVISION_REQ);
	}
	//PNP_ID
	else if(evt.uuid == BLE_PNP_ID_UUID)
	{

		SetGATTReadRsp(PNP_ID_LEN, (uint8_t *)PNP_ID_REQ);
	}
	//System ID
	else if(evt.uuid == BLE_SYSTEM_ID_UUID)
	{
		struct gap_ble_addr addr;
		uint8_t SYSTEM_ID[8];
		
		GetDevAddr(&addr);
		
		SYSTEM_ID[0]=addr.addr[0];
		SYSTEM_ID[1]=addr.addr[1];
		SYSTEM_ID[2]=addr.addr[2];
		
		SYSTEM_ID[3]=0;
		SYSTEM_ID[4]=0;
		
		SYSTEM_ID[5]=addr.addr[3];
		SYSTEM_ID[6]=addr.addr[4];
		SYSTEM_ID[7]=addr.addr[5];
		SetGATTReadRsp(8, (uint8_t *)SYSTEM_ID);
	}
	//OTA Read/Write
	else if(evt.uuid == BLE_OTA_Read_Write_UUID)
	{
		uint8_t sz=0;
		uint8_t rsp[sizeof(struct hci_evt)]={0};

		ota_rsp(rsp, &sz);
		
		//ota_event_flag = 1;

		SetGATTReadRsp(sz, rsp);
	}
	//Vendor_V1
	else if(evt.uuid == BLE_VendorV2)
	{
		uint8_t gatt_buf[]="Vendor Service";
		uint8_t gatt_buf_sz = sizeof(gatt_buf); 

		SetGATTReadRsp(gatt_buf_sz, gatt_buf);
	}
	
	else if(evt.hdl == BLE_VendorV2_Notify_User_Description)
	{
		SetGATTReadRsp(VENDORV2_NOTIFY_DESCRIPTION_LEN, (uint8_t *)VENDORV2_NOTIFY_DESCRIPTION_REQ);
	}
	
	else if(evt.hdl == BLE_VendorV2_Write_User_Description)
	{
		SetGATTReadRsp(VENDORV2_WRITE_DESCRIPTION_LEN, (uint8_t *)VENDORV2_WRITE_DESCRIPTION_REQ);
	}
	else
	{//other_UUID
		struct gap_ble_addr addr1;
		uint8_t SYSTEM_ID1[8];
		
		GetDevAddr(&addr1);
		
		SYSTEM_ID1[0]=addr1.addr[0];
		SYSTEM_ID1[1]=addr1.addr[1];
		SYSTEM_ID1[2]=addr1.addr[2];
		
		SYSTEM_ID1[3]=0;
		SYSTEM_ID1[4]=0;
		
		SYSTEM_ID1[5]=addr1.addr[3];
		SYSTEM_ID1[6]=addr1.addr[4];
		SYSTEM_ID1[7]=addr1.addr[5];
		SetGATTReadRsp(8, (uint8_t *)SYSTEM_ID1);		
	}
}

static void ble_gatt_write(struct gap_att_write_evt evt)
{
	
	if(evt.uuid == BLE_VendorV2_Write_UUID)
	{
		// rx data
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGHEXDUMP("Recive:\r\n",evt.data,evt.sz);
		#endif
		gatt_Recive_flag = 1;		//接收到了数据
		
		gatt_buff[0] = (uint8_t)evt.sz;
		memcpy(&gatt_buff[1], evt.data, evt.sz);
		
	}
	
	else if(evt.uuid== BLE_OTA_Read_Write_UUID)
	{
		ota_cmd(evt.data, evt.sz);
		//ota_connect_evnet();
		ota_event_flag = 1;
	}
}

void ble_evt_callback(struct gap_ble_evt *p_evt)
{	
	if(p_evt->evt_code == GAP_EVT_ATT_WRITE)
	{
		#ifdef _GPIO_LED_CONTROL_
		GPIO_Pin_Turn(U32BIT(GPIO_LED_WRITEING));
		#endif
		
		ble_gatt_write(p_evt->evt.att_write_evt);
	}
	else if(p_evt->evt_code == GAP_EVT_ATT_READ)
	{
		#ifdef _GPIO_LED_CONTROL_
		GPIO_Pin_Turn(U32BIT(GPIO_LED_READING));
		#endif
		
		ble_gatt_read(p_evt->evt.att_read_evt);
	}
	else if(p_evt->evt_code == GAP_EVT_CONNECTED)
	{
		connect_flag=1;								 //连接状态
		
		//update_latency=1;
		
		update_latency_mode = 0;
		
		Timer_Evt_Start(EVT_2S);
		
		gap_connect_evnet();
		
		#ifdef _GPIO_LED_CONTROL_
		GPIO_Pin_Set(U32BIT(GPIO_LED_CONNECT));
		#endif
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGHEXDUMP("Connected addr:",p_evt->evt.bond_dev_evt.addr,sizeof(p_evt->evt.bond_dev_evt.addr));
		#endif
	}
	else if(p_evt->evt_code == GAP_EVT_DISCONNECTED)
	{		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGPRINTF("Disconnected,reson:0x%02x\r\n", p_evt->evt.disconn_evt.reason);
		#endif
		
		connect_flag=0;
		
		Timer_Evt_Stop(EVT_1S_OTA);
		//setup_adv_data();		//断开连接之后功耗大10uA
		//StartAdv();	
		
		gap_disconnect_evnet();
		
		#ifdef _GPIO_LED_CONTROL_
		GPIO_Pin_Clear(U32BIT(GPIO_LED_CONNECT));
		GPIO_Pin_Clear(U32BIT(GPIO_LED_NOTIFYEN));
		#endif
		
		// UartEn(false);	//不允许RF sleep时关闭XO
		//DBGPRINTF(("start adv @ disc!\r\n")); 
	}
	else if(p_evt->evt_code == GAP_EVT_ATT_HANDLE_CONFIGURE)
	{
		if(p_evt->evt.att_handle_config_evt.uuid == BLE_VendorV2)
		{
			if(p_evt->evt.att_handle_config_evt.value == BLE_GATT_NOTIFICATION)		//允许notify
			{
				start_tx = 0x01;
				//UartEn(true);	//不允许RF sleep时关闭XO，休眠的时候因为32Mhz晶振还在，所以功耗很高
				
				#ifdef _GPIO_LED_CONTROL_
				GPIO_Pin_Set(U32BIT(GPIO_LED_NOTIFYEN));
				#endif
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				DBGPRINTF(("UART notify Enabled!\r\n"));
				#endif
			}
			else		//不允许notify
			{
				start_tx = 0x00;
				//UartEn(false);	//允许硬件自由控制32Mhz晶振，休眠的时候功耗很低
				
				#ifdef _GPIO_LED_CONTROL_
				GPIO_Pin_Clear(U32BIT(GPIO_LED_NOTIFYEN));
				#endif
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				DBGPRINTF(("UART notify Disabled!\r\n"));
				#endif
			}
		}
	}
	else if(p_evt->evt_code == GAP_EVT_L2CAP_UPDATE_RSP)
	{
		switch(p_evt->evt.l2cap_update_rsp_evt.result)
		{
			case CONN_PARAMS_ACCEPTED:
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_) 
				DBGPRINTF(("update rsp ACCEPTED\r\n"));
				{
					struct gap_link_params  link_app;
					GetLinkParameters(&link_app);
					#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
					dbg_printf("interval:%x latency:%x\r\n",link_app.interval,link_app.latency);
					#endif
				}
				#endif
				break;
			case CONN_PARAMS_REJECTED:
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				DBGPRINTF(("update rsp REJECTED\r\n"));
				#endif
				break;
			case CONN_PARAM_SMART_TIMEROUT:
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				DBGPRINTF(("update rsp TIMEROUT\r\n"));
				#endif
				break;
			case CONN_PARAM_SMART_SUCCEED:
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				DBGPRINTF(("update rsp SUCCEED\r\n"));
				#endif
				break;
			case CONN_PARAM_LATENCY_ENABLE:
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				DBGPRINTF(("Enable latency\r\n"));
				#endif
				break;
			case CONN_PARAM_LATENCY_DISABLE:
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				DBGPRINTF(("Disable latency\r\n"));
				#endif
				break;
		}
	}
	else if(p_evt->evt_code == GAP_EVT_CONN_UPDATE_COMP)
	{
		struct gap_link_params link;
		GetLinkParameters(&link);
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGPRINTF("CONN_UPDATE_COMP: %d, %d, %d\r\n", link.interval, link.latency, link.svto);
		#endif
	}
	else if(p_evt->evt_code == GAP_EVT_ADV_END)
	{
		StartAdv();
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGPRINTF(("GAP_EVT_ADV_END\r\n"));
		#endif
	}
}

static void ble_init(void)
{	
	struct gap_evt_callback evt;		
	struct smp_pairing_req sec_params;	
//	struct gap_wakeup_config pw_cfg;

	BleInit();	
	SetWinWideMinusCnt(1);

	GetGATTReportHandle(&g_report);

	/* security parameters */
	sec_params.io = IO_NO_INPUT_OUTPUT;
	sec_params.oob = OOB_AUTH_NOT_PRESENT;
	sec_params.flags = AUTHREQ_BONDING;
	sec_params.mitm = 0;
	sec_params.max_enc_sz = 16;
	sec_params.init_key = 0;
	sec_params.rsp_key = (SMP_KEY_MASTER_IDEN |SMP_KEY_ADDR_INFO);
	SetSecParams(&sec_params);
	
	evt.evt_mask=(GAP_EVT_CONNECTION_SLEEP|GAP_EVT_CONNECTION_INTERVAL);
	evt.p_callback=&ble_evt_callback;
	SetEvtCallback(&evt);

	/* Bond Manager (MAX:10) */
	SetBondManagerIndex(0x00);
	if(ble_status==0)
		DelAllBondDevice();

	setup_adv_data();
	/*
	当POWERDOWN_WAKEUP时，
	PW_CTRL->DSLP_LPO_EN = true，这些中断源才能唤醒并复位运行，
	如果是false就只有pin能唤醒并复位运行
	*/
//	pw_cfg.wakeup_type = SLEEP_WAKEUP;
//	pw_cfg.wdt_wakeup_en = (bool)false;
//	pw_cfg.rtc_wakeup_en = (bool)true;
//	pw_cfg.timer_wakeup_en = (bool)false;
//	pw_cfg.gpi_wakeup_en = (bool)true;
//	pw_cfg.gpi_wakeup_cfg = WAKEUP_PIN;	//中断唤醒pin
//	WakeupConfig(&pw_cfg);
}

void  ota_manage(void){
	#ifdef _OTA_
	if(ota_state){
		wdt_disable();
		switch(ota_state){
			case 1 : 
				CmdFwErase();
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("OTA start\r\n");
				#endif
				ota_state =2;
				ota_writecnt=0;
				break;
			case 2 : 
				ota_writecnt++;
				if(ota_writecnt>0x20)
				{
					#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
						dbg_printf("OTA faile\r\n");
					#endif
					Timer_Evt_Stop(EVT_1S_OTA);
				}
				break;
			case 3 : 
				ota_state=0;
				Timer_Evt_Stop(EVT_1S_OTA);
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
					dbg_printf("OTA finish\r\n");
				#endif
				delay_ms(100);
			#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("Start Reset 1000s\r\n");
			#endif
				SystemReset();
				delay_ms(1000);
				break;
			default :
				break;
		}
	}
	#endif
}


/************************************************************************************************************************
* 函数名称: rf_stop()
* 功能说明: 停止广播
* 输 入: 无
* 输 出: 无
* 注意事项: 无
************************************************************************************************************************/
void rf_stop(void)
{
	RFSleep();
	ble_status = 2;		//Stop 
}

/************************************************************************************************************************
* 函数名称: rf_restart()
* 功能说明: 重启广播
* 输 入: 无
* 输 出: 无
* 注意事项: 无
************************************************************************************************************************/
void rf_restart(void)
{
	if(ble_status==2)	//是停止状态
	{
		RFWakeup();
		DelayMS(100);
//		UartEn(true);
		ble_init();
		DelayMS(100);
//		UartEn(false);

		StartAdv();
	}
	else if(ble_status==0)
	{
		StartAdv();
		
	}
	
	ble_status = 1;
}



void nvic_priority(void){
	NVIC_SetPriority(LLC_IRQn   , 2);
	NVIC_SetPriority(RTC_IRQn  , 2);
	NVIC_SetPriority(SW_IRQn  , 2);
	NVIC_SetPriority(I2C0_IRQn , 2);
	NVIC_SetPriority(I2C1_IRQn , 2);
	NVIC_SetPriority(UART0_IRQn, 2);
	NVIC_SetPriority(UART1_IRQn, 2);
	NVIC_SetPriority(TIMER0_IRQn, 2);
	NVIC_SetPriority(TIMER1_IRQn, 2);
	NVIC_SetPriority(TIMER2_IRQn  , 2);
	NVIC_SetPriority(TIMER3_IRQn  , 2);
	NVIC_SetPriority(GPIO_IRQn   , 2);
	NVIC_SetPriority(HID_IRQn  , 2);
	NVIC_SetPriority(SPIM_IRQn  , 2);
	NVIC_SetPriority(CAP_IRQn , 0);
	NVIC_SetPriority(GPADC_IRQn , 2);
	NVIC_SetPriority(LLC2_IRQn, 2);
	NVIC_SetPriority(ISO7816_IRQn, 2);
	NVIC_SetPriority(IR_Tx_IRQn, 2);
	NVIC_SetPriority(TOUCH_IRQn, 2);
	NVIC_SetPriority(HPWM_IRQn  , 2);
	NVIC_SetPriority(HTIMER_IRQn  , 2);
	NVIC_SetPriority(IR_Rx_IRQn  , 2);
}

int main(void)
{
		u8 i;
		__disable_irq();	

		ble_init();  //蓝牙初始化，系统主时钟初始化64M,32K时钟初始化为LPO
		nvic_priority();   //把串口优先级设置到最高
		
		//根据需要重新设置时钟为4M并校准
		MCUClockSwitch(SYSTEM_CLOCK_64M_RCOSC);
		RCOSCCalibration();

		#ifdef USER_32K_CLOCK_RCOSC
			ClockSwitch(SYSTEM_32K_CLOCK_RCOSC);
			GAPBBDelayMS(500);
			LPOCalibration();						//这是内部RC32k晶振的校准函数	经过该函数后定时器能够得到一个比较准确的值
		#else
			ClockSwitch(SYSTEM_32K_CLOCK_XOSC);
		#endif
			
		#ifdef _SYD_RTT_DEBUG_
				DebugLogInit();
		#endif
			
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
			dbg_printf("Syd8811_LOCK %s:%s\r\n",__DATE__ ,__TIME__);
		#endif
		//software:timr0   blelib timer1
		SYD_Timer_Init(EVT_NUM, syd_sysTimer);	
		Timer_Evt_List();

		SYD_RTC_Init(RTCEVT_NUM, syd_rtc);	
		RTC_Evt_List();
		
		gpio_init();			//低功耗时GPIO的初始统一设置
		
		UartEn(false);	//
		
		hardware_init();
		
		//StartAdv();		//开始广播
	
		rf_stop();
	
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
			DBGPRINTF(("LORA_BLE_V1101\r\n"));
		#endif
			
			OS_timer_SetEvt(EVT_ENTER_SLEEP);		//进入休眠
			
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
			dbg_printf("Enter Sleep:0x%08x\r\n" ,TIMER_EVENT);
		#endif

		GetDevAddr(&_user.ble_ID);//get ble SN	

	 //EraseFlashData(0, 1);//DEBUGGG YCY
		ReadProfileData(0, DATA_SIZE, data_read); //ReadFlashData(0, DATA_SIZE, data_read);	//从机需读主机ID

		eep_save.savedata.head[0] = data_read[0];
		eep_save.savedata.head[1] = data_read[1];			
		eep_save.savedata.lock_id.data8[0] = data_read[2];
		eep_save.savedata.lock_id.data8[1] = data_read[3];
		eep_save.savedata.lock_id.data8[2] = data_read[4];
		eep_save.savedata.lock_id.data8[3] = data_read[5];	
		eep_save.savedata.lock_id.data8[4] = data_read[6];
		eep_save.savedata.lock_id.data8[5] = data_read[7];		
		eep_save.savedata.lock_id.data8[6] = data_read[8];
		eep_save.savedata.lock_id.data8[7] = data_read[9];		
		eep_save.savedata.key_code[0] = data_read[10];
		eep_save.savedata.key_code[1] = data_read[11];		
		eep_save.savedata.key_code[2] = data_read[12];
		eep_save.savedata.key_code[3] = data_read[13];		
		eep_save.savedata.key_code[4] = data_read[14];
		eep_save.savedata.key_code[5] = data_read[15];				
		eep_save.savedata.IP[0] = data_read[16];
		eep_save.savedata.IP[1] = data_read[17];		
		eep_save.savedata.IP[2] = data_read[18];
		eep_save.savedata.IP[3] = data_read[19];	
		eep_save.savedata.port=(data_read[20]<<8) | data_read[21];
		
		if((eep_save.savedata.head[0] == 0xAA)  && (eep_save.savedata.head[1] == eep_ver))
		{
				for(i=0;i<8;i++){
				_user.lock_sn8array.data8[i] = eep_save.savedata.lock_id.data8[i];
				}
				dbg_printf("flg_eepOK\r\n");
				flg_eepOK = 1;
			
			//YCY 260101 FIXED IP
			//for(i=0;i<4;i++){
			//	eep_save.savedata.IP[i] =  ip_tab[i];
			//}
			//eep_save.savedata.port = port_value;
				
		}
		else
		{
			for(i=0;i<8;i++){
				eep_save.savedata.lock_id.data8[i] =_user.lock_sn8array.data8[i] = sn_tab[i];
			}

			for(i=0;i<6;i++){
				eep_save.savedata.key_code[i] = keycode_tab[i];
			}

			for(i=0;i<4;i++){
				eep_save.savedata.IP[i] =  ip_tab[i];
			}
			eep_save.savedata.port = port_value;

			eep_save.savedata.head[0] = 0xAA;
			eep_save.savedata.head[1] = eep_ver;		
			flg_eepOK = 0;	//读取NG
			dbg_printf("flg_eepERR\r\n");
		}	
		
		// net4G.senddelaytime_set = 10; // LoRa: 4G removed//发送间隔10秒

 		wdt_enable(128*10);	//10s	 以内喂狗即可 256/32.768 = 7.8ms		
		__enable_irq();
	
	while(1)
	{
		ble_sched_execute();	//协议栈任务
		
		if(TIMER_EVT) 
		{
			#ifdef	EVT_1S_OTA
			if(TIMER_EVT&EVT_1S_OTA)   
			{
				ota_manage();     //ota 过程管理 操作屏幕等
				Timer_Evt_Clr(EVT_1S_OTA);
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				DBGPRINTF(("EVT_1S_OTA\r\n"));
				#ifdef _WDT_
				 wdt_clear();
				#endif
				#endif
			}
			#endif
			#ifdef	EVT_2S
			if(TIMER_EVT&EVT_2S)
			{
				Connection_latency();   //连接参数相关设置
				Timer_Evt_Clr(EVT_2S);
			}
			#endif
		}
		if(RTC_EVT) 
    {
			#ifdef	RTCEVT_whole_minute
			if(RTC_EVT&RTCEVT_whole_minute)
			{					
			#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
			//Time_clock_Struct time;
			//timeAppClockGet((void *)&time);
			 //DBGPRINTF_TIMER(("RTCEVT_whole_minute:%d-%d-%d:%d:%d:%d\r\n",time.year_low,time.month,time.day,time.hour,time.minutes,time.seconds));
				
				DBGPRINTF(("RTCEVT_1min\r\n"));
			#endif

			RTC_EVT_Clr(RTCEVT_whole_minute);
			}
			#endif
			#ifdef	RTCEVT_10S
			if(RTC_EVT&RTCEVT_10S)
			{
				#ifdef _WDT_
				 wdt_clear();
				#endif

				TimerNSEvent();//260101+

				RTC_EVT_Clr(RTCEVT_10S);
			}
			#endif
			#ifdef	RTCEVT_185S
			if(RTC_EVT&RTCEVT_185S)
			{
				#ifdef USER_32K_CLOCK_RCOSC
					 //if(connect_flag && (latency_state==1)) gap_s_connection_latency_mode(0x00);
					 LPOCalibration();						//这是内部RC32k晶振的校准函数	经过该函数后定时器能够得到一个比较准确的值
					 //if(connect_flag && (latency_state==1)) gap_s_connection_latency_mode(0x01);
				#endif
				
 				wdt_clear();//喂一次狗 185s一次
				
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				 DBGPRINTF(("RTCEVT_185S\r\n"));
				#endif
				RTC_EVT_Clr(RTCEVT_185S);
			}
			#endif
			
			#ifdef RTCEVT_72h
			if(RTC_EVT&RTCEVT_72h)
			{
				RTC_EVT_Clr(RTCEVT_72h);		
				if(Systerm_States==SLEEP)		//如果系统处于睡眠状态，则开始放电
				{
					//Systerm_States |= BATDISCHG;
					WakeupSource |= RTC_ALARM;
				}
				else
				{
					RTC_EVT_Stop(RTCEVT_72h);	//其他情况停止事件
				}
			}
			#endif

			#ifdef RTCEVT_24h
			if(RTC_EVT&RTCEVT_24h)
			{
				RTC_EVT_Clr(RTCEVT_24h);		
				if(Systerm_States==SLEEP)		//如果系统处于睡眠状态，则开始放电
				{
					WakeupSource |= RTC_4Greport;
				}
				else
				{
					RTC_EVT_Stop(RTCEVT_24h);	//
				}
			}
			#endif

		}
		if( ota_state == 1)  ota_manage();     //OTA擦除命令到来，马上擦除
		
		if(gatt_Recive_flag)
		{
			gatt_Recive_flag = 0;
			GATT_ReadWrite_Process();
		}
		
		if(ota_event_flag)
		{
			ota_event_flag = 0;
			ota_connect_evnet();
			Systerm_States = OTAMODE;
		}
		
		if(Systerm_States != OTAMODE)
		{
			// test_wakeup();
			// user_init();
						
			user_wakeup();
			user_task();
			// Uart_TX(); // LoRa: 4G removed

			if(flg_reset){
				__disable_irq();
				flg_reset = 0;
				delay_ms(100);
				SystemReset();
				delay_ms(1000);
				while(1);
			}
			
			if(Systerm_States==SLEEP)
			{
				Timer_Evt_Stop(EVT_1S_OTA);
				Timer_Evt_Stop(EVT_2S);
				//LedBlink_Fast();
				//WakupConfig_BeforeSleep();
				//SPCStop();
				UartEn(false);
				GPIO_Pin_Clear(U32BIT(PWR_4G));	
				flg_4g_EN = 0;
				flg_cutup = 0;
				SystemSleep();				//系统睡眠
				delay_ms(10);
			}
		}


		if(flg_10ms)
		{
			flg_10ms = 0;
 		}

		if(flg_flashwrite){
			flg_flashwrite = 0;
			__disable_irq();	
			wdt_clear();
		 //YCY-//EraseFlashData(0, 1);//

			
			for(i=0;i < DATA_SIZE;i++){
				data_write[i] = eep_save.eepdata_8[i];
			}		
			data_write[0] = eep_save.savedata.head[0];
			data_write[1] = eep_save.savedata.head[1];			

			data_write[2] = eep_save.savedata.lock_id.data8[0];
			data_write[3] = eep_save.savedata.lock_id.data8[1];		
			data_write[4] = eep_save.savedata.lock_id.data8[2];
			data_write[5] = eep_save.savedata.lock_id.data8[3];		
			data_write[6] = eep_save.savedata.lock_id.data8[4];
			data_write[7] = eep_save.savedata.lock_id.data8[5];		
			data_write[8] = eep_save.savedata.lock_id.data8[6];
			data_write[9] = eep_save.savedata.lock_id.data8[7];		

			data_write[10] = eep_save.savedata.key_code[0];
			data_write[11] = eep_save.savedata.key_code[1];		
			data_write[12] = eep_save.savedata.key_code[2];
			data_write[13] = eep_save.savedata.key_code[3];		
			data_write[14] = eep_save.savedata.key_code[4];
			data_write[15] = eep_save.savedata.key_code[5];				


			data_write[16] = eep_save.savedata.IP[0];
			data_write[17] = eep_save.savedata.IP[1];		
			data_write[18] = eep_save.savedata.IP[2];
			data_write[19] = eep_save.savedata.IP[3];	

			data_write[20] = (u8)(eep_save.savedata.port>>8);
			data_write[21] = (u8)eep_save.savedata.port;		
			WriteProfileData(0, DATA_SIZE, data_write);//YCY WriteFlashData(0,DATA_SIZE, data_write); 	
		  ble_sched_execute();

			//wdt_clear();			
			__enable_irq();
			if(flg_setipOK){
				flg_setipOK = 0;
				flg_reset = 1;
			}
		
		}
	}
}

void HardFault_Handler(void)
{	
	#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGPRINTF(("ERR: HardFault!\r\n"));	
	#endif
	delay_ms(100);
	SystemReset();
	delay_ms(1000);
	while(1);
}

