module RedmineHelpdeskMailHandlerPatch
  private
  # Overrides the dispatch_to_default method to
  # set the owner-email of a new issue created by
  # an email request
  def dispatch_to_default
    issue = receive_issue
    roles = if issue.author.class == AnonymousUser
      Role.where(builtin: issue.author.id)
    else
      issue.author.roles_for_project(issue.project)
    end

    #73540 Email to Support@mypmstudio.com - Assign the Ticket to Correct Project
    #Is a user in the system with TI Email address - assign the ticket to them
    sender_email = @email.from.first.to_s
    if sender_email.include?("@targetintegration.com") || (User.current.projects.size == 1 && issue.project == User.current.projects.first)
      issue.update_columns({assigned_to_id: User.current.id})
    end

    # add owner-email only if the author has assigned some role with
    # permission treat_user_as_supportclient enabled
    if issue.author.type.eql?("AnonymousUser") || roles.any? {|role| role.allowed_to?(:treat_user_as_supportclient) }
      sender_email = @email.from.first

      # any cc handling needed?
      custom_value = custom_field_value(issue.project,'cc-handling')
      if (!@email.cc.nil?) && (custom_value.value == '1')
        carbon_copy = @email[:cc].formatted.join(', ')
        custom_value = custom_field_value(issue,'copy-to')
        custom_value.value = carbon_copy
        custom_value.save(:validate => false)
      else
        carbon_copy = nil
      end

      issue.reload
      issue.description = email_details + issue.description
      issue.save

      custom_value = custom_field_value(issue,'owner-email')
      if custom_value.value.to_s.strip.empty?
        custom_value.value = sender_email
        custom_value.save(:validate => false) # skip validation!
      else
        # Email owner field was already set by some preprocess hooks.
        # So now we need to send message to another recepient.
        sender_email = custom_value.value.to_s.strip
      end

      # regular email sending to known users is done
      # on the first issue.save. So we need to send
      # the notification email to the supportclient
      # on our own.
      HelpdeskMailer.email_to_supportclient(
        issue, {
          :recipient => sender_email,
          :carbon_copy => carbon_copy
        }
      ).deliver
    end
    after_dispatch_to_default_hook issue
    return issue
  end

  # let other plugins the chance to override this
  # method to hook into dispatch_to_default
  def after_dispatch_to_default_hook(issue)
  end

  # Fix an issue with email.has_attachments?
  def add_attachments(obj)
     if !email.attachments.nil? && email.attachments.size > 0
       email.attachments.each do |attachment|
         obj.attachments << Attachment.create(:container => obj,
                           :file => attachment.decoded,
                           :filename => attachment.filename,
                           :author => user,
                           :content_type => attachment.mime_type)
      end
    end
  end

  # Overrides the receive_issue_reply method
  def receive_issue_reply(issue_id, from_journal=nil)
    issue = Issue.find_by_id(issue_id)
    return unless issue

    # reopening a closed issues by email
    custom_value = custom_field_value(issue.project,'reopen-issues-with')
    if issue.closed?
      reopen_status_id = ''
      if custom_value.present? && custom_value.value.present?
        reopen_status_id = IssueStatus.where("name = ?", custom_value.value).try(:first).try(:id)
      end
      if reopen_status_id.blank?
        reopen_status_id = IssueStatus.find_by_id(2).try(:id)
        reopen_status_id = IssueStatus.where("name = ?", "In Progress").try(:first).try(:id) if reopen_status_id.blank?
      end
      if reopen_status_id.present?
        issue.update(status_id: reopen_status_id)
        # issue.assigned_to = nil
        # issue.save
      end
    else
      if issue.assigned_to_id.present? && issue.status.try(:name).to_s == "Waiting for Customer"
        user_members = issue.project.memberships.where(user_id: user.id)
        if user_members.present?
          user_roles = user_members.map(&:roles).flatten.map(&:name).compact.uniq
          if user_roles.include?("Customer")
            assig_members = issue.project.memberships.where(user_id: issue.assigned_to.id)
            if assig_members.present?
              assig_roles = assig_members.map(&:roles).flatten.map(&:name).compact.uniq
              if assig_roles.include?("Customer")
                memberships = issue.project.memberships.shuffle
                ms = memberships.detect do |mem|
                  mem.roles.map(&:name).include?("Second Lead Consultant")
                end
                if ms.present?
                  issue.update(assigned_to_id: ms.user_id)
                end
              end
            end
          end
        end
      end
    end

    # call original method
    super

    # store email-details before each note
    last_journal = Journal.find(issue.last_journal_id)
    last_journal.notes = email_details + last_journal.notes
    last_journal.save

    return last_journal
  end

  def custom_field_value(issue,name)
    custom_field = CustomField.find_by_name(name)
    CustomValue.where(
      "customized_id = ? AND custom_field_id = ?", issue.id, custom_field.id
    ).first
  end

  def email_details
    details =  "From: " + @email[:from].formatted.first + "\n"
    details << "To:   " + @email[:to].formatted.join(', ') + "\n" if !@email.to.nil?
    details << "Cc:   " + @email[:cc].formatted.join(', ') + "\n" if !@email.cc.nil?
    details << "Date: " + @email[:date].to_s + "\n"
    "<pre>\n" + Mail::Encodings.unquote_and_convert_to(details, 'utf-8') + "</pre>\n\n"
  end

end # module RedmineHelpdeskMailHandlerPatch

# Add module to MailHandler class
MailHandler.prepend(RedmineHelpdeskMailHandlerPatch)
