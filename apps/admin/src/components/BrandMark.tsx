export function BrandMark() {
  return <span className="brand-mark" aria-label="CampusAI"><i className="brand-node"/><i className="brand-node"/><i className="brand-node"/><i className="brand-node"/><i className="brand-node"/></span>
}

const symbols: Record<string, string> = {
  add: '\ue145', blur_on: '\ue3a5', campaign: '\uef49', dashboard: '\ue871',
  fact_check: '\uf0c5', filter_list: '\ue152', flag: '\uf0c6', forum: '\ue8af',
  group: '\uea21', login: '\uea77', menu: '\ue5d2', more_horiz: '\ue5d3',
  notifications: '\ue7f5', person: '\uf0d3', policy: '\uea17', receipt_long: '\uef6e',
  refresh: '\ue5d5', rocket_launch: '\ueb9b', search: '\uef7a', sort: '\ue164',
  storefront: '\uea12',
}

export function Symbol({ children, filled = false }: { children: string; filled?: boolean }) {
  const paths:Record<string,string>={key:'M14 7a5 5 0 1 1-3 9L4 23H1v-3l8-8a5 5 0 0 1 5-5Z M17 10h.01',download:'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5',chevron_right:'m9 5 7 7-7 7',arrow_forward:'M4 12h16m-6-6 6 6-6 6',close:'m6 6 12 12M18 6 6 18',logout:'M9 4H4v16h5m5-13 5 5-5 5M8 12h11',clock:'M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',visibility:'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12 M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',lock:'M7 10V7a5 5 0 0 1 10 0v3M5 10h14v11H5Zm7 4v3'}
  if(paths[children])return <svg className="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[children]}/></svg>
  return <span className="material-symbols-rounded" aria-hidden="true" style={filled ? { fontVariationSettings: "'FILL' 1, 'wght' 450, 'GRAD' 0, 'opsz' 24" } : undefined}>{symbols[children] ?? symbols.dashboard}</span>
}
