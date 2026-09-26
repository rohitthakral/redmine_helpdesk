#
# With Rails 3 mail is send with the mail method. Sadly redmine
# uses this method-name too in their mailer. This is the reason
# why we need our own Mailer class.
#
class HelpdeskMailer < ActionMailer::Base
  helper :application

  include Redmine::I18n
  include MacroExpander

  # set the hostname for url_for helper
  def self.default_url_options
    { :host => Setting.host_name, :protocol => Setting.protocol }
  end

  # Sending email notifications to the supportclient
  def email_to_supportclient(issue, params)
    # issue, recipient, journal=nil, text='', copy_to=nil

    recipient = params[:recipient]
    journal = params[:journal]
    text = params[:text]
    carbon_copy = params[:carbon_copy]
    first_reply = params[:first_reply]

    redmine_headers 'Project' => issue.project.identifier,
                    'Issue-Id' => issue.id,
                    'Issue-Author' => issue.author.login
    redmine_headers 'Issue-Assignee' => issue.assigned_to.login if issue.assigned_to
    message_id issue
    references issue

    subject = "[#{issue.project.name} - ##{issue.id}] #{issue.subject}"
    # Set 'from' email-address to 'helpdesk-sender-email' if available.
    # Falls back to regular redmine behaviour if 'sender' is empty.
    p = issue.project
    s = CustomField.find_by_name('helpdesk-sender-email')
    sender = p.custom_value_for(s).try(:value) if p.present? && s.present?
    # If a custom field with text for the first reply is
    # available then use this one instead of the regular
    r = CustomField.find_by_name('helpdesk-first-reply')
    f = CustomField.find_by_name('helpdesk-email-footer')
    reply  = p.nil? || r.nil? ? '' : p.custom_value_for(r).try(:value)
    footer = p.nil? || f.nil? ? '' : p.custom_value_for(f).try(:value)
    # add carbon copy
    ct = CustomField.find_by_name('copy-to')
    if carbon_copy.nil?
      carbon_copy = issue.custom_value_for(ct).try(:value)
    end
    # add any attachements (only the ones added with this journal)
    sent_attachments = []
    if journal.present? && text.present?
      journal.details.each do |d|
        next unless d.property == 'attachment' && d.value.present?
        a = Attachment.find_by(:id => d.prop_key)
        next if a.nil?
        sent_attachments << a
        begin
          attachments[a.filename] = {
            :mime_type => a.content_type.presence || 'application/octet-stream',
            :content   => File.binread(a.diskfile)
          }
        rescue => e
          Rails.logger.error "Helpdesk: could not attach #{a.filename} (##{a.id}) to supportclient email: #{e.message}"
        end
      end
    end
    if @message_id_object
      headers[:message_id] = "<#{self.class.message_id_for(@message_id_object)}>"
    end
    if @references_objects
      headers[:references] = @references_objects.collect {|o| "<#{self.class.references_for(o)}>"}.join(' ')
    end
    # create mail object to deliver
    mail = if text.present? || first_reply
      # sending out the journal note to the support client
      # or the first reply message
      reply = default_first_reply(issue) if first_reply && reply.blank?
      t = if text.present?
        "#{text}\n\n#{footer}"
      elsif footer.present?
        "#{reply}\n\n#{footer}"
      else
        reply
      end
      body = expand_macros(t, issue, journal)

      # precess reply-separator
      f = CustomField.find_by_name('helpdesk-reply-separator')
      reply_separator = issue.project.custom_value_for(f).try(:value)
      if !reply_separator.blank?
        body = reply_separator + "\n\n" + body
      end

      # add download links, so the files are reachable even if the
      # delivery method drops the mail attachments
      if sent_attachments.any?
        links = sent_attachments.map do |a|
          url = helpdesk_attachment_download_url(
            :id => a.id,
            :token => HelpdeskAttachmentLink.token_for(a),
            :filename => a.filename
          )
          "- #{a.filename}: #{url}"
        end
        body = "#{body}\n\n#{l(:label_attachment_plural)}:\n#{links.join("\n")}"
      end

      mail(
        :from     => sender.present? && sender || Setting.mail_from,
        :reply_to => sender.present? && sender || Setting.mail_from,
        :to       => recipient,
        :subject  => subject,
        :body     => body,
        :date     => Time.zone.now,
        :cc       => carbon_copy
      )
    else
      # fallback to a regular notifications email with redmine view
      @issue = issue
      @journal = journal
      @issue_url = url_for(:controller => 'issues', :action => 'show', :id => issue)
      mail(
        :from     => sender.present? && sender || Setting.mail_from,
        :reply_to => sender.present? && sender || Setting.mail_from,
        :to       => recipient,
        :subject  => subject,
        :date     => Time.zone.now,
        :template_path => 'mailer',
        :template_name => 'issue_edit',
        :cc            => carbon_copy
      )
    end
    # return mail object to deliver it
    return mail
  end

  private

  # Appends a Redmine header field (name is prepended with 'X-Redmine-')
  def redmine_headers(h)
    h.each { |k,v| headers["X-Redmine-#{k}"] = v.to_s }
  end

  def self.token_for(object, rand=true)
    timestamp = object.send(object.respond_to?(:created_on) ? :created_on : :updated_on)
    hash = [
      "redmine",
      "#{object.class.name.demodulize.underscore}-#{object.id}",
      timestamp.strftime("%Y%m%d%H%M%S")
    ]
    if rand
      hash << Redmine::Utils.random_hex(8)
    end
    host = Setting.mail_from.to_s.strip.gsub(%r{^.*@|>}, '')
    host = "#{::Socket.gethostname}.redmine" if host.empty?
    "#{hash.join('.')}@#{host}"
  end

  # Returns a Message-Id for the given object
  def self.message_id_for(object)
    token_for(object, true)
  end

  # Returns a uniq token for a given object referenced by all notifications
  # related to this object
  def self.references_for(object)
    token_for(object, false)
  end

  def message_id(object)
    @message_id_object = object
  end

  def references(object)
    @references_objects ||= []
    @references_objects << object
  end

  def default_first_reply(issue)
    "Your case ##issue-id## has been created."
  end
end
