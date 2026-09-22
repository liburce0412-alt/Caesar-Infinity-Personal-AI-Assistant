import { createRootRoute, createRoute, createRouter, lazyRouteComponent, Outlet, redirect } from '@tanstack/react-router'
import { AppShell } from './components/AppShell'
const DataPage = lazyRouteComponent(() => import('./pages/DataPage'), 'DataPage')
const InvitationsPage = lazyRouteComponent(() => import('./pages/InvitationsPage'), 'InvitationsPage')
const LoginPage = lazyRouteComponent(() => import('./pages/LoginPage'), 'LoginPage')
const OverviewPage = lazyRouteComponent(() => import('./pages/OverviewPage'), 'OverviewPage')
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
const contentRoute=createRoute({getParentRoute:()=>shellRoute,path:'/content',component:()=> <DataPage key="content" kind="content"/>})
const listingsRoute=createRoute({getParentRoute:()=>shellRoute,path:'/listings',component:()=> <DataPage key="listings" kind="listings"/>})
const ordersRoute=createRoute({getParentRoute:()=>shellRoute,path:'/orders',component:()=> <DataPage key="orders" kind="orders"/>})
const reportsRoute=createRoute({getParentRoute:()=>shellRoute,path:'/reports',component:()=> <DataPage key="reports" kind="reports"/>})
const announcementsRoute=createRoute({getParentRoute:()=>shellRoute,path:'/announcements',component:()=> <DataPage key="announcements" kind="announcements"/>})
const releasesRoute=createRoute({getParentRoute:()=>shellRoute,path:'/releases',component:()=> <DataPage key="releases" kind="releases"/>})
const auditRoute=createRoute({getParentRoute:()=>shellRoute,path:'/audit',component:()=> <DataPage key="audit" kind="audit"/>})
const routeTree=rootRoute.addChildren([loginRoute,shellRoute.addChildren([overviewRoute,invitesRoute,usersRoute,contentRoute,listingsRoute,ordersRoute,reportsRoute,announcementsRoute,releasesRoute,auditRoute])])
export const router=createRouter({routeTree,defaultPreload:'intent'})
declare module '@tanstack/react-router' { interface Register { router:typeof router } }
