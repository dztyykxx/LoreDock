import { createApp } from 'vue'
import App from './App.vue'
import { setUnauthorizedHandler } from './api/http'
import { projectApi } from './api/projects'
import { knowledgeApi } from './api/knowledge'
import { knowledgeApiKey, projectApiKey, sessionKey } from './appContext'
import { createLoreDockRouter } from './router'
import { createSessionController } from './session/useSession'
import './style.css'

const session = createSessionController()
setUnauthorizedHandler(session.clear)

createApp(App)
  .use(createLoreDockRouter(session))
  .provide(sessionKey, session)
  .provide(projectApiKey, projectApi)
  .provide(knowledgeApiKey, knowledgeApi)
  .mount('#app')
