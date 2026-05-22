module RedmineHelpdesk
  module Patches
    module ProjectsHelperPatch
      def project_settings_tabs
        tabs = super

        if redmine_helpdesk_defaults_tab_visible?
          tabs << {
            :name => 'helpdesk_defaults',
            :action => :edit_project,
            :partial => 'projects/settings/helpdesk_defaults',
            :label => :label_helpdesk_defaults
          }
        end

        tabs
      end

      private

      def redmine_helpdesk_defaults_tab_visible?
        @project &&
          (User.current.admin? || User.current.allowed_to?(:edit_project, @project))
      end
    end
  end
end

RedmineHelpdeskProjectsHelperPatch = RedmineHelpdesk::Patches::ProjectsHelperPatch unless defined?(RedmineHelpdeskProjectsHelperPatch)
