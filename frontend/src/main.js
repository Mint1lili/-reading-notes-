import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'

/**
 * 应用入口：Vue 3 + Pinia + Element Plus（中文语言包）。
 * Element Plus 全量注册，未做按需引入——当前页面量少，换更小的首屏包可以后续再做。
 */
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
