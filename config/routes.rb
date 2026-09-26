put 'projects/:id/helpdesk_defaults',
    :to => 'redmine_helpdesk_project_settings#update',
    :as => 'redmine_helpdesk_project_settings'

get 'helpdesk/attachments/:id/:token/:filename',
    :to => 'helpdesk_attachments#download',
    :as => 'helpdesk_attachment_download',
    :id => /\d+/,
    :filename => /.*/,
    :format => false
