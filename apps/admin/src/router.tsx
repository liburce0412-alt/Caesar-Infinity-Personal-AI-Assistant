import { createRootRoute, createRoute, createRouter, Outlet, redirect } from '@tanstack/react-router'
import { AppShell } from './components/AppShell'
import { DataPage } from './pages/DataPage'
import { InvitationsPage } from './pages/InvitationsPage'
import { LoginPage } from './pages/LoginPage'
import { OverviewPage } from './pages/OverviewPage'
import { hasAdminRole, isBackendConfigured, backend } from './lib/backend'

const rootRoute=createRootRoute({component:()=> <Outlet/>})
const loginRoute=createRoute({getParentRoute:()=>rootRoute,path:'/login',component:LoginPage})
const shellRoute=createRoute({
  getParentRoute:()=>rootRoute,
  id:'shell',
  beforeLoad: async () => {
    // Local visual work remains available without environment credentials. Every
    // configured deployment requires both a valid session and a server-owned role.
    if (!isBackendConfigured || !backend) return
    const { data } = await backend.auth.getSession()
    const user = data.session?.user
    if (!user || !(await hasAdminRole(user.id))) {
      if (user) await backend.auth.signOut()
      throw redirect({ to:'/login' })
    }
  },
  component:AppShell,
})
const overviewRoute=createRoute({getParentRoute:()=>shellRoute,path:'/',component:OverviewPage})
const invitesRoute=createRoute({getParentRoute:()=>shellRoute,path:'/invites',component:InvitationsPage})
const usersRoute=createRoute({getParentRoute:()=>shellRoute,path:'/users',component:()=> <DataPage key="users" kind="users"/>})
const contentRoute=createRoute({getParentRoute:()=>shellRoute,path:'/content',component:()=> <DataPage kind="content"/>})
const listingsRoute=createRoute({getParentRoute:()=>shellRoute,path:'/listings',component:()=> <DataPage kind="listings"/>})
const ordersRoute=createRoute({getParentRoute:()=>shellRoute,path:'/orders',component:()=> <DataPage kind="orders"/>})
const reportsRoute=createRoute({getParentRoute:()=>shellRoute,path:'/reports',component:()=> <DataPage kind="reports"/>})
const announcementsRoute=createRoute({getParentRoute:()=>shellRoute,path:'/announcements',component:()=> <DataPage kind="announcements"/>})
const releasesRoute=createRoute({getParentRoute:()=>shellRoute,path:'/releases',component:()=> <DataPage kind="releases"/>})
const auditRoute=createRoute({getParentRoute:()=>shellRoute,path:'/audit',component:()=> <DataPage kind="audit"/>})
const routeTree=rootRoute.addChildren([loginRoute,shellRoute.addChildren([overviewRoute,invitesRoute,usersRoute,contentRoute,listingsRoute,ordersRoute,reportsRoute,announcementsRoute,releasesRoute,auditRoute])])
export const router=createRouter({routeTree,defaultPreload:'intent'})
declare module '@tanstack/react-router' { interface Register { router:typeof router } }
