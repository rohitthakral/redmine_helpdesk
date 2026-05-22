module RedmineHelpdesk
  def self.apply_patches
    require_dependency 'projects_helper'
    require_dependency 'redmine_helpdesk_projects_helper_patch'

    unless ProjectsHelper.ancestors.include?(RedmineHelpdesk::Patches::ProjectsHelperPatch)
      ProjectsHelper.send(:prepend, RedmineHelpdesk::Patches::ProjectsHelperPatch)
    end
  end
end
