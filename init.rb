require 'redmine'
$LOAD_PATH.unshift "#{File.dirname(__FILE__)}/lib"
require 'redmine_helpdesk'
require 'helpdesk_hooks'
require 'helpdesk_attachment_link'
require 'helpdesk_mailer'
require 'redmine_helpdesk_project_defaults'
require 'redmine_helpdesk_journal_patch'
require 'redmine_helpdesk_mail_handler_patch'
require 'redmine_helpdesk_mailer_patch'

Redmine::Plugin.register :redmine_helpdesk do
  name 'Redmine helpdesk plugin'
  author 'Stefan Husch'
  description 'Redmine helpdesk plugin'
  version '0.0.19'
  requires_redmine :version_or_higher => '4.0.0'
  project_module :issue_tracking do
    permission :treat_user_as_supportclient, {}
  end
end

if defined?(Rails.configuration.to_prepare)
  Rails.configuration.to_prepare do
    RedmineHelpdesk.apply_patches
  end
else
  ActionDispatch::Callbacks.to_prepare do
    RedmineHelpdesk.apply_patches
  end
end

Rails.application.config.after_initialize do
  RedmineHelpdesk.apply_patches
end
